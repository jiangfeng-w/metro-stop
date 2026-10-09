@file:Suppress("DEPRECATION") // PhoneStateListener 是 API < 31 的兜底通路（平台弃用但 minSdk 26 必需）

package com.metrostop.reminder.platform.lab

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.metrostop.reminder.core.model.TuningConfig
import java.util.concurrent.Executor

/**
 * 蜂窝小区流引擎（**C1 拍点加密**，主链路与 lab 共用；两流差异只在出拍格式与出口）：
 *
 * - **被动路**：`TelephonyCallback.CellInfoListener`（API 31+）/ `PhoneStateListener`（API < 31 兜底，
 *   平台已弃用但 minSdk 26 必需）；回调变化即取一拍；
 * - **主动路**：`requestCellInfoUpdate`（API 29+）按 `cellPollIntervalSec` 定时取拍——被动回调在
 *   息屏/后台会塌缩（2026-10-08 晚实测 1 拍/27~49 s），主动轮询保证拍点密度；`onError`
 *   （限速 / 基带 / 超时）只记日志不打断循环，**允许失败**（平台不保证有返回）；
 * - **节流**：身份签名（[LabCellCollector.identitySignature]，`pci:ci` + 主服务标记）变化立即出拍，
 *   否则按 `minIntervalMs`（1 s）节流——**rssi 抖动不再破节流**（签名拆分，P0-3：让断档指标可信）；
 * - **活性检测**：连续 `cellStreamSilentWarnSec` 无样本 → [onEvent] 报 `CELL_STREAM_SILENT` 并重挂
 *   被动路（自愈尝试）；静默期内只报一次，恢复后重新武装（判定路径另有站区门活性回退兜底）；
 * - 线程：内部 `HandlerThread("cell-info-stream")`，回调 / 轮询 / [sink] 都在该线程（调用方自行
 *   跨线程投递到会话线程）。
 */
internal class CellInfoStreamEngine(
    context: Context,
    private val config: TuningConfig,
    private val sink: (elapsedMs: Long, utcMs: Long, snaps: List<LabCellCollector.CellSnapshot>) -> Unit,
    private val onEvent: (type: String, detail: String) -> Unit,
) {

    private val appContext = context.applicationContext

    @Volatile
    private var telephony: TelephonyManager? = null
    private var thread: HandlerThread? = null
    @Volatile
    private var handler: Handler? = null
    @Volatile
    private var legacyListener: PhoneStateListener? = null
    @Volatile
    private var callback: TelephonyCallback? = null

    @Volatile
    private var lastEmitElapsedMs = 0L
    @Volatile
    private var lastIdentity: String? = null
    @Volatile
    private var lastSampleElapsedMs = 0L
    @Volatile
    private var silenceWarned = false
    private var pollErrorCount = 0
    private var lastPollErrorEventMs = 0L

    private val pollRunnable = Runnable { poll() }

    /** [start] 结果：两个调用方对失败的落盘 / 降级语义不同，失败原因必须区分（保留既有诊断事件） */
    internal enum class StartResult { OK, NO_TELEPHONY_SERVICE, LISTEN_FAILED }

    /** 挂被动路 + 定时主动轮询 + 首拍；失败原因见 [StartResult]（调用方降级 / 落事件） */
    fun start(): StartResult {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return StartResult.NO_TELEPHONY_SERVICE
        telephony = tm
        val ht = HandlerThread("cell-info-stream")
        ht.start()
        thread = ht
        val h = Handler(ht.looper)
        handler = h
        if (runCatching { registerPassive(tm, h) }.isFailure) {
            stop()
            return StartResult.LISTEN_FAILED
        }
        onCells(runCatching { tm.allCellInfo }.getOrNull().orEmpty())
        scheduleNextPoll()
        return StartResult.OK
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        val tm = telephony
        telephony = null
        if (tm != null) {
            callback?.let { cb ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { tm.unregisterTelephonyCallback(cb) }
                }
            }
            legacyListener?.let { l ->
                @Suppress("DEPRECATION")
                runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
            }
        }
        callback = null
        legacyListener = null
        thread?.quitSafely()
        thread = null
    }

    // ---------------------------------------------------------------- 被动路

    private fun registerPassive(tm: TelephonyManager, h: Handler) {
        val executor = Executor { r -> h.post(r) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = runCatching {
                val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        onCells(cellInfo)
                    }
                }
                callback = cb
                tm.registerTelephonyCallback(executor, cb)
            }.isSuccess
            if (ok) return
            // 平台兼容层（HyperOS 等）拒绝新 API → 回退旧回调（本机基线通路，真机已验证）
            callback = null
            onEvent("CELL_LISTEN_FALLBACK", "telephony_callback_failed")
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        val l = object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                onCells(cellInfo ?: emptyList())
            }
        }
        legacyListener = l
        @Suppress("DEPRECATION")
        tm.listen(l, PhoneStateListener.LISTEN_CELL_INFO)
    }

    private fun unregisterPassive() {
        val tm = telephony ?: return
        callback?.let { cb ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
        }
        callback = null
        legacyListener?.let { l ->
            @Suppress("DEPRECATION")
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        legacyListener = null
    }

    // ---------------------------------------------------------------- 主动路

    private fun scheduleNextPoll() {
        val h = handler ?: return
        h.postDelayed(pollRunnable, (config.cellPollIntervalSec * 1000).toLong())
    }

    private fun poll() {
        val tm = telephony ?: return
        val h = handler ?: return
        // 活性检测：过窗无样本 → 报一次 + 重挂被动路（自愈）
        val now = SystemClock.elapsedRealtime()
        if (silenceDue(now, lastSampleElapsedMs, silenceWarned, (config.cellStreamSilentWarnSec * 1000).toLong())) {
            silenceWarned = true
            onEvent("CELL_STREAM_SILENT", "no_samples_${config.cellStreamSilentWarnSec.toInt()}s")
            runCatching { unregisterPassive(); registerPassive(tm, h) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tm.requestCellInfoUpdate(
                    Executor { r -> h.post(r) },
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            onCells(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            // 限速 / 基带 / 超时都属预期（平台不保证有返回）——不打断轮询，只按窗口留痕
                            pollErrorCount++
                            val nowMs = SystemClock.elapsedRealtime()
                            if (LabWifiCollector.shouldWriteFailEvent(nowMs, lastPollErrorEventMs, POLL_ERROR_EVENT_THROTTLE_MS)) {
                                lastPollErrorEventMs = nowMs
                                onEvent(
                                    "CELL_POLL_ERROR",
                                    "count=$pollErrorCount code=$errorCode detail=${detail?.javaClass?.simpleName ?: "null"}",
                                )
                            }
                        }
                    },
                )
            }
        }
        scheduleNextPoll()
    }

    // ---------------------------------------------------------------- 出拍

    private fun onCells(cells: List<CellInfo>) {
        val now = SystemClock.elapsedRealtime()
        lastSampleElapsedMs = now
        silenceWarned = false
        val snaps = LabCellCollector.sortedCells(LabCellCollector.parseCells(cells))
        val identity = LabCellCollector.identitySignature(snaps)
        // 身份变化即出拍（切换不落节流窗）；身份不变走 1 Hz 节流（rssi 抖动不破节流）
        if (!LabCellCollector.shouldWrite(now, lastEmitElapsedMs, identity, lastIdentity)) return
        lastIdentity = identity
        lastEmitElapsedMs = now
        sink(now, System.currentTimeMillis(), snaps)
    }

    companion object {
        /** 轮询失败事件的写盘节流（与 wifi 失败事件同口径；轮询错误可能每拍都来） */
        const val POLL_ERROR_EVENT_THROTTLE_MS = 60_000L

        /**
         * 静默告警判定（纯函数可测）：有过样本（首拍前不报）后连续超窗，且本次静默期未报过 → 报一次。
         * 任一观测（回调 / 轮询有返回）都会清 warned（见 [onCells]），恢复后重新武装。
         */
        internal fun silenceDue(nowMs: Long, lastSampleMs: Long, warned: Boolean, warnMs: Long): Boolean =
            !warned && lastSampleMs != 0L && nowMs - lastSampleMs >= warnMs
    }
}
