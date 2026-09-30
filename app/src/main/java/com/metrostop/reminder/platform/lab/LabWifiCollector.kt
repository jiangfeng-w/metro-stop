package com.metrostop.reminder.platform.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Locale

/**
 * lab 采集的 Wi-Fi 指纹流（cellular-wifi-fingerprint-validate，**旁路工具，与监测主链路零交集**）：
 *
 * - 数据源：系统扫描完成广播 [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] → 当前
 *   [WifiManager.getScanResults] 快照；**1 Hz 节流，缺失秒不补行**（需求 3.1）；
 * - 行格式：`t_ms,utc_ms,ap_count,direct_count,bssid_hash_list`（哈希 `|` 分隔）；
 *   `direct_count` = SSID 以 `DIRECT-` 开头的个数（Wi-Fi Direct P2P 信标，到站相关性 H2 的对象）；
 * - **隐私（需求 3.3，本仓库 PUBLIC）**：BSSID 一律 [hashBssid]（SHA-1 截 10 hex）落盘，原 MAC 不入库；
 *   SSID 只保留「是否 DIRECT- 前缀」布尔，不落原文；
 * - **扫描节奏（2026-09-29 修复，见 `wifi-collector-fix` 需求）**：**30 s 固定节拍器**发起
 *   [WifiManager.startScan]（Handler 驱动，与广播回调解耦）——早通勤实测「回调即续扫」在
 *   HyperOS 上形成每秒数百次的重扫风暴（65 万次请求 100% 被限频拒绝、事件流 71.6 MB，
 *   疑被自身风暴触发持续限频自锁）。回调里**不再续扫**，只写快照与失败证据；
 * - 限频证据（H4）：请求被系统拒绝（返回 false）落 `WIFI_STARTSCAN_FAIL`、扫描本身失败
 *   （`EXTRA_RESULTS_UPDATED=false`）落 `WIFI_SCAN_ERROR`——两类事件**60 s 节流**（保留累计计数），
 *   节流本身不掩盖失败总量；
 * - 权限降级由调用方（LabCollectorService 权限矩阵）负责：API 33+ 需 `NEARBY_WIFI_DEVICES`
 *   （manifest 声明 `neverForLocation`：扫描结果不用于定位，仅做指纹哈希），更低版本需定位权限；
 * - **wifi 空快照结案（2026-09-30，H1/H4 关闭）**：HyperOS 4 beta 上 App 进程内
 *   `isWifiEnabled` 恒 false 而 settings `wifi_on=1`、shell 扫描正常，`getScanResults` 恒空，
 *   与 neverForLocation / INTERNET 权限增删均无关（四轮对照实验）——系统 WifiManager
 *   兼容层异常/抑制。`WIFI_DIAG` 事件（30 s 节拍随行，双通道读数）保留供节后系统
 *   更新后重验；重验通过前 wifi 指纹方向不投入。
 */
class LabWifiCollector(
    context: Context,
    private val write: (stream: String, row: String) -> Unit,
    private val writeEvent: (type: String, detail: String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var wifi: WifiManager? = null
    private var receiver: BroadcastReceiver? = null
    private var tickerRunning = false

    private var lastRowElapsedMs = 0L
    private var lastRequestElapsedMs = 0L
    private var lastFailEventElapsedMs = 0L
    private var startScanFails = 0
    private var scanErrors = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            requestScan()
            handler.postDelayed(this, MIN_RESCAN_INTERVAL_MS)
        }
    }

    /** 注册扫描广播并触发首次扫描；失败返回 false（调用方从 activeStreams 移除 wifi） */
    fun start(): Boolean {
        val wm = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: run {
            writeEvent("WIFI_START", "no_wifi_service")
            return false
        }
        wifi = wm

        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                // EXTRA_RESULTS_UPDATED=false 表示这次扫描本身失败（计数即限频/失败证据）
                if (i?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) == false) {
                    scanErrors++
                    if (shouldWriteFailEvent(nowElapsed(), lastFailEventElapsedMs, FAIL_EVENT_THROTTLE_MS)) {
                        lastFailEventElapsedMs = nowElapsed()
                        writeEvent("WIFI_SCAN_ERROR", "count=$scanErrors")
                    }
                }
                writeSnapshot()
                // 不在回调里续扫（重扫风暴根因）；节奏由 30 s 节拍器统一驱动
            }
        }
        receiver = rx
        val registered = runCatching {
            ContextCompat.registerReceiver(
                appContext, rx,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (!registered) {
            writeEvent("WIFI_START", "register_failed")
            receiver = null
            wifi = null
            return false
        }
        val enabled = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        writeEvent("WIFI_START", "scan_results_listener;wifi_enabled=$enabled;rescan_interval_s=${MIN_RESCAN_INTERVAL_MS / 1000}")
        tickerRunning = true
        requestScan()
        handler.postDelayed(ticker, MIN_RESCAN_INTERVAL_MS)
        return true
    }

    fun stop() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        val rx = receiver ?: return
        runCatching { appContext.unregisterReceiver(rx) }
        receiver = null
        wifi = null
    }

    private fun nowElapsed() = SystemClock.elapsedRealtime()

    @Suppress("DEPRECATION") // startScan() 自 API 31 标记废弃但仍可用；无公开替代（H4 限频证据本身要靠它）
    private fun requestScan() {
        val now = nowElapsed()
        if (!shouldRequestScan(now, lastRequestElapsedMs, MIN_RESCAN_INTERVAL_MS)) return
        lastRequestElapsedMs = now
        // 结案诊断（2026-09-30，保留供节后系统更新重验）：双通道开关读数——
        // WifiManager.isWifiEnabled 实测恒 false 而 settings wifi_on=1，读数分离
        // 即为系统 WifiManager 兼容层异常的直接证据。
        writeEvent(
            "WIFI_DIAG",
            "wifi_mgr=${runCatching { wifi?.isWifiEnabled }.getOrDefault(false)}" +
                ",settings_on=${settingsWifiOn()}",
        )
        val ok = runCatching { wifi?.startScan() }.getOrDefault(false) == true
        if (!ok) {
            startScanFails++
            if (shouldWriteFailEvent(now, lastFailEventElapsedMs, FAIL_EVENT_THROTTLE_MS)) {
                lastFailEventElapsedMs = now
                writeEvent("WIFI_STARTSCAN_FAIL", "count=$startScanFails")
            }
        }
    }

    /** settings 层的 Wi-Fi 开关（绕过 WifiManager 直读 Global.WIFI_ON，异常/缺失返回 -1） */
    private fun settingsWifiOn(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.WIFI_ON)
    }.getOrDefault(-1)

    @Suppress("DEPRECATION") // SSID 字段自 API 33 标记废弃；替代 getWifiSsid() 只在系统权限下可用
    private fun writeSnapshot() {
        val wm = wifi ?: return
        val now = SystemClock.elapsedRealtime()
        // 1 Hz 节流：回调比 1 s 密时只取最新快照，缺失秒不补行
        if (now - lastRowElapsedMs < 1000L) return
        val results = runCatching { wm.scanResults }.getOrDefault(emptyList())
        lastRowElapsedMs = now
        var direct = 0
        val hashes = ArrayList<String>(results.size)
        for (r in results) {
            if (r.SSID?.startsWith("DIRECT-") == true) direct++
            val bssid = r.BSSID
            if (!bssid.isNullOrEmpty()) hashes += hashBssid(bssid)
        }
        val row = "$now,${System.currentTimeMillis()},${results.size},$direct,${hashes.joinToString("|")}"
        write("wifi", row)
    }

    companion object {
        /** 两次主动扫描请求的最小间隔：后台限频（4 次/2 min）下仍保证每站间区间 ≥3 次尝试（H4） */
        const val MIN_RESCAN_INTERVAL_MS = 30_000L

        /** 失败类事件（STARTSCAN_FAIL / SCAN_ERROR）的落盘节流：保留总量计数，不刷屏 */
        const val FAIL_EVENT_THROTTLE_MS = 60_000L

        /** 扫描请求节流决策：距上次请求不足 [minIntervalMs] 时不再请求（纯 JVM 可测） */
        fun shouldRequestScan(nowMs: Long, lastRequestMs: Long, minIntervalMs: Long): Boolean =
            nowMs - lastRequestMs >= minIntervalMs

        /** 失败事件节流决策：首次必写，之后 [throttleMs] 内静默（纯 JVM 可测） */
        fun shouldWriteFailEvent(nowMs: Long, lastEventMs: Long, throttleMs: Long): Boolean =
            lastEventMs == 0L || nowMs - lastEventMs >= throttleMs

        /**
         * BSSID → SHA-1 十六进制前 10 字符（40 bit；站内 AP 规模下冲突可忽略）。
         * 先小写规范化：同一 AP 大小写不同的呈现必须得到同一哈希。纯 JVM 可测。
         */
        fun hashBssid(bssid: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(bssid.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
            return sb.substring(0, 10)
        }
    }
}
