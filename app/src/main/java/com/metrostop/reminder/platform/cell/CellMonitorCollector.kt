package com.metrostop.reminder.platform.cell

import android.content.Context
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.metrostop.reminder.platform.lab.LabCellCollector

/**
 * 监测主链路的蜂窝小区流（v4，cell-zone-detector-v4）。
 *
 * 复用 [LabCellCollector] 的归一化 / 排序 / 节流纯函数（与 lab cellid 流**同一口径**，
 * 离线分析可互换）；区别只在出口：样本回调给 [MonitorService]（进会话融合层 + 会话
 * `cell_<stamp>.csv` 落盘），不再写 lab 目录。
 *
 * - 数据源：`LISTEN_CELL_INFO` 回调 + 启动首拍；**1 Hz 节流 + 变化即写**（与 lab cellid 一致）；
 * - 身份只取 `pci:ci`（rssi 不参与匹配——信号强度波动不是小区切换）；
 * - 线程：回调在 telephony binder 线程，[onSample] 需自行切到会话线程
 *   （服务侧用 ConcurrentLinkedQueue + 传感器线程排空，保证会话单线程访问）；
 * - 权限：`READ_PHONE_STATE`（运行时已授；未授时 start 返回 false，调用方降级 = v3 行为）。
 */
class CellMonitorCollector(
    context: Context,
    private val onSample: (tMs: Long, utcMs: Long, main: String?, neighbors: List<String>, cellsRaw: String) -> Unit,
) {

    private val appContext = context.applicationContext
    private var telephony: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    private var lastRowElapsedMs = 0L
    private var lastSignature: String? = null

    /** 注册 CellInfo 监听并回调首拍；失败返回 false（调用方降级为无小区数据） */
    @Suppress("DEPRECATION")
    fun start(): Boolean {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        telephony = tm
        val l = object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                onCells(cellInfo ?: emptyList())
            }
        }
        listener = l
        val ok = runCatching { tm.listen(l, PhoneStateListener.LISTEN_CELL_INFO) }.isSuccess
        if (!ok) {
            listener = null
            telephony = null
            return false
        }
        onCells(runCatching { tm.allCellInfo }.getOrNull().orEmpty())
        return true
    }

    fun stop() {
        val tm = telephony
        val l = listener
        if (tm != null && l != null) {
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        telephony = null
        listener = null
    }

    private fun onCells(cells: List<CellInfo>) {
        val snaps = LabCellCollector.sortedCells(LabCellCollector.parseCells(cells))
        val signature = LabCellCollector.cellsSignature(snaps)
        val now = SystemClock.elapsedRealtime()
        // 变化即写（切换不落节流窗）；签名不变才走 1 Hz 节流（与 lab cellid 同口径）
        if (!LabCellCollector.shouldWrite(now, lastRowElapsedMs, signature, lastSignature)) return
        lastSignature = signature
        lastRowElapsedMs = now
        val ids = snaps.map { "${it.pci}:${it.ci}" }
        onSample(now, System.currentTimeMillis(), ids.firstOrNull(), ids.drop(1), LabCellCollector.formatCells(snaps))
    }
}
