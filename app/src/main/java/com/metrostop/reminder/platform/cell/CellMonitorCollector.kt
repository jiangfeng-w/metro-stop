package com.metrostop.reminder.platform.cell

import android.content.Context
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.platform.lab.CellInfoStreamEngine
import com.metrostop.reminder.platform.lab.LabCellCollector

/**
 * 监测主链路的蜂窝小区流（v4，cell-zone-detector-v4）。
 *
 * 复用 [LabCellCollector] 的归一化 / 排序 / 节流纯函数与 [CellInfoStreamEngine] 采集引擎
 * （与 lab cellid 流**同一口径**，离线分析可互换）；区别只在出口：样本回调给 [MonitorService]
 * （进会话融合层 + 会话 `cell_<stamp>.csv` 落盘），不再写 lab 目录。
 *
 * - 数据源（**C1 起**）：`LISTEN_CELL_INFO` 回调（API 31+ 为 `TelephonyCallback`，旧版兜底）
 *   + `requestCellInfoUpdate` **主动轮询**（API 29+，`cellPollIntervalSec`）+ 启动首拍——
 *   被动回调在息屏/后台会塌缩（10-08 晚 1 拍/27~49 s），主动轮询保证拍点密度；
 * - **1 Hz 节流 + 身份签名变化即出拍**（`pci:ci` + 主服务标记；rssi 抖动不破节流）；
 * - 身份只取 `pci:ci`（rssi 不参与匹配——信号强度波动不是小区切换）；
 * - 活性：连续 `cellStreamSilentWarnSec` 无样本 → [onEvent] `CELL_STREAM_SILENT` + 重挂被动路
 *   （自愈；判定路径另有站区门活性回退兜底），轮询失败按窗口报 `CELL_POLL_ERROR`；
 * - 线程：引擎内部 HandlerThread，[onSample] 需自行切到会话线程
 *   （服务侧用 ConcurrentLinkedQueue + 传感器线程排空，保证会话单线程访问）；
 * - 权限：`READ_PHONE_STATE`（运行时已授；未授时 start 返回 false，调用方降级 = v3 行为）。
 */
class CellMonitorCollector(
    context: Context,
    private val onSample: (tMs: Long, utcMs: Long, main: String?, neighbors: List<String>, cellsRaw: String) -> Unit,
    private val onEvent: (type: String, detail: String) -> Unit = { _, _ -> },
    private val config: TuningConfig = TuningConfig.Default,
) {

    private val appContext = context.applicationContext
    private var engine: CellInfoStreamEngine? = null

    /** 注册监听 + 主动轮询并回调首拍；失败返回 false（调用方降级为无小区数据） */
    fun start(): Boolean {
        val e = CellInfoStreamEngine(
            appContext,
            config,
            sink = { elapsedMs, utcMs, snaps ->
                val ids = snaps.map { "${it.pci}:${it.ci}" }
                onSample(elapsedMs, utcMs, ids.firstOrNull(), ids.drop(1), LabCellCollector.formatCells(snaps))
            },
            onEvent = onEvent,
        )
        engine = e
        if (e.start() != CellInfoStreamEngine.StartResult.OK) {
            engine = null
            return false
        }
        return true
    }

    fun stop() {
        engine?.stop()
        engine = null
    }
}
