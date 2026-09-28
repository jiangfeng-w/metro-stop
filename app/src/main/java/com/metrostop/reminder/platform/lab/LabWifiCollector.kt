package com.metrostop.reminder.platform.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Locale

/**
 * lab 采集的 Wi-Fi 指纹流（cellular-wifi-fingerprint-validate，**旁路工具，与监测主链路零交集**）：
 *
 * - 数据源：系统扫描完成广播 [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] → 当前
 *   [WifiManager.getScanResults] 快照；**每次回调触发，1 Hz 节流，缺失秒不补行**（需求 3.1）；
 * - 行格式：`t_ms,utc_ms,ap_count,direct_count,bssid_hash_list`（哈希 `|` 分隔）；
 *   `direct_count` = SSID 以 `DIRECT-` 开头的个数（Wi-Fi Direct P2P 信标，到站相关性 H2 的对象）；
 * - **隐私（需求 3.3，本仓库 PUBLIC）**：BSSID 一律 [hashBssid]（SHA-1 截 10 hex）落盘，原 MAC 不入库；
 *   SSID 只保留「是否 DIRECT- 前缀」布尔，不落原文；
 * - 限频证据（H4）：每次回调后立即续 [WifiManager.startScan]——系统按自身节流拒绝时
 *   失败次数落事件流（`WIFI_STARTSCAN_FAIL`），扫描本身失败落 `WIFI_SCAN_ERROR`；
 * - 权限降级由调用方（LabCollectorService 权限矩阵）负责：API 33+ 需 `NEARBY_WIFI_DEVICES`，
 *   更低版本需定位权限（manifest 声明 `neverForLocation`：扫描结果不用于定位，仅做指纹哈希）。
 */
class LabWifiCollector(
    context: Context,
    private val write: (stream: String, row: String) -> Unit,
    private val writeEvent: (type: String, detail: String) -> Unit,
) {

    private val appContext = context.applicationContext
    private var wifi: WifiManager? = null
    private var receiver: BroadcastReceiver? = null

    private var lastRowElapsedMs = 0L
    private var startScanFails = 0
    private var scanErrors = 0

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
                    writeEvent("WIFI_SCAN_ERROR", "count=$scanErrors")
                }
                writeSnapshot()
                // 收到结果立即续扫：实际间隔由系统节流决定（拒绝也落事件，正是 H4 要的数据）
                requestScan()
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
        writeEvent("WIFI_START", "scan_results_listener;wifi_enabled=$enabled")
        requestScan()
        return true
    }

    fun stop() {
        val rx = receiver ?: return
        runCatching { appContext.unregisterReceiver(rx) }
        receiver = null
        wifi = null
    }

    @Suppress("DEPRECATION") // startScan() 自 API 31 标记废弃但仍可用；无公开替代（H4 限频证据本身要靠它）
    private fun requestScan() {
        val ok = runCatching { wifi?.startScan() }.getOrDefault(false) == true
        if (!ok) {
            startScanFails++
            writeEvent("WIFI_STARTSCAN_FAIL", "count=$startScanFails")
        }
    }

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
