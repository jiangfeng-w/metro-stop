package com.metrostop.reminder.core.fsm

import com.metrostop.reminder.core.cell.CellZoneLine
import com.metrostop.reminder.core.cell.CellZoneTracker
import com.metrostop.reminder.core.cell.ZoneTransition
import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.DetectorState
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig

/** 一次 tick / 纠错的输出：事件 + 是否需要结束监测 */
data class TickOutcome(
    val events: List<DetectorEvent> = emptyList(),
    val endRequested: Boolean = false,
    val endReason: String? = null,
    /** 排定的自动结束时刻（到站延时结束） */
    val scheduledEndAtMs: Long? = null,
)

/**
 * 会话聚合（总纲 5.4）：把状态机的物理到站事件翻译成**路线语义下的计数与提醒**。
 *
 * - `k = destinationIndex − boardingIndex`；`n` = 已确认到站数；
 * - `n == k − 1` → ALERT_PREV（去重只发一次）；`n == k` → ALERT_ARRIVED 并排定 endGraceSec 后结束；
 * - `n > k` → OVERSHOOT（只发一次）；
 * - `k == 1` 特例：预热结束即发 ALERT_PREV；
 * - 首站不计数（hasRun=false，视为上车站本身）、两站最短间隔门槛（STOP_SUSPECT）；
 * - 手动纠错 ±1：纠错到 k−1 / k / >k 时**同样补发对应提醒**（2026-09-28 实测缺口修复），
 *   且纠错时刻更新间隔门槛锚点 lastCountedMs；最长 90 分钟兜底结束。
 *
 * v4 融合层（cell-zone-detector-v4，蜂窝回答「在哪」、IMU 回答「停没停」）：
 * - `zoneLine` 非空时启用站区跟踪；两个开关（`cellZoneGateEnabled` / `cellZoneScoutEnabled`）
 *   默认 false = **影子模式**（跟踪器照常记录 ZONE_* 事件，判定行为与 v3 完全一致）；
 * - **站区门**：到站候选必须落在包含期望下一站的站区，否则 ZONE_SUPPRESSED（信号停车免疫）；
 * - **重同步**：站区首站位 > 期望位（前一站被漏检）→ 候选直接对齐到站区首站位，防级联失步；
 * - **侦察补检**：区内放宽静稳（`zoneStillVibTh`/`zoneStillConfirmSec`）补检原始阈值下的静默漏检站；
 * - **目的站兜底**：进目的站区后 `zoneArrivalFallbackSec` 仍无静稳计数（用户走动噪声）→ 产生到站候选；
 * - **D−1 提前**：进入目的站前一站的站区即发 ALERT_PREV（比停稳计数早 0~58 s）。
 */
class MonitorSession(
    val route: RouteSpec,
    val config: TuningConfig = TuningConfig.Default,
    zoneLine: CellZoneLine? = null,
) {
    private val detector = StationStopDetector(config)
    private val zoneTracker = CellZoneTracker(zoneLine, config)

    var stationCount: Int = 0
        private set
    var startedAtMs: Long = 0L
        private set
    var running: Boolean = false
        private set
    var arrived: Boolean = false
        private set

    private var scheduledEndAtMs: Long? = null
    private var alertPrevSent = false
    private var alertArrivedSent = false
    private var overshootSent = false
    private var lastCountedMs = 0L
    private var lastDwellS: Double? = null

    // ---------- v4 融合层状态 ----------
    /** tick 间时长（侦察静稳跨度用） */
    private var prevTickMs = 0L

    /** 区内放宽静稳跨度（起点 / 最后静稳时刻；起点 0 = 未开启） */
    private var zoneStillStartMs = 0L
    private var zoneStillLastMs = 0L

    /** 进入目的站区的时刻（>0 = 兜底计时中） */
    private var destZoneAtMs = 0L

    val state get() = detector.state
    val dataGapCount get() = detector.dataGapCount

    /** 经过的站数 k */
    val stopCount: Int get() = route.stopCount

    fun start(nowMs: Long): TickOutcome {
        running = true
        startedAtMs = nowMs
        stationCount = 0
        arrived = false
        alertPrevSent = false
        alertArrivedSent = false
        overshootSent = false
        lastCountedMs = 0L
        scheduledEndAtMs = null
        prevTickMs = 0L
        zoneStillStartMs = 0L
        zoneStillLastMs = 0L
        destZoneAtMs = 0L
        val events = ArrayList<DetectorEvent>(detector.start(nowMs))
        return TickOutcome(events = events)
    }

    /**
     * 喂一条小区样本（platform 监听 / 回放合并循环调用；时间轴与 [tick] 同源）。
     * 返回产生的 ZONE_* / 提醒事件（调用方负责落盘 / 通知）。
     */
    fun onCellSample(tMs: Long, mainCell: String?, neighbors: List<String>): List<DetectorEvent> {
        if (!running || !zoneTracker.active) return emptyList()
        val out = ArrayList<DetectorEvent>(2)
        for (tr in zoneTracker.onCell(tMs, mainCell, neighbors)) {
            when (tr) {
                is ZoneTransition.Entered -> {
                    val range = tr.range
                    out += DetectorEvent(
                        tMs = tr.tMs,
                        type = DetectorEventType.ZONE_CONFIRMED,
                        stationIndex = route.currentStationIndex(range.first),
                        stationName = zoneDisplayName(range),
                        note = "range=${range.first}..${range.last}",
                    )
                    // D−1 提前提醒（评审 P4 定稿）：进入目的站前一站的站区、驻留确认即发
                    if (route.stopCount > 1 && (route.stopCount - 1) in range) {
                        emitPreAlert(tr.tMs)?.let { out += it }
                    }
                    // 目的站兜底计时起点（进区早于停稳 0~58 s，故以进区为锚）
                    if (route.stopCount in range && stationCount < route.stopCount) {
                        destZoneAtMs = tr.tMs
                    }
                }

                is ZoneTransition.Exited -> {
                    out += DetectorEvent(
                        tMs = tr.tMs,
                        type = DetectorEventType.ZONE_EXITED,
                        note = "range_ended",
                    )
                    destZoneAtMs = 0L
                }
            }
        }
        return out
    }
    /**
     * 处理一条特征样本。**时间只来自样本**（tMs），保证回放一致。
     */
    fun tick(f: Features): TickOutcome {
        if (!running) return TickOutcome()
        val events = ArrayList<DetectorEvent>(4)

        // ---- 兜底：最长监测时长 ----
        if (detector.elapsedSec(f.tMs) >= config.maxMonitorMin * 60.0) {
            detector.finish(f.tMs, EndReason.TIMEOUT)
            running = false
            events += DetectorEvent(
                tMs = f.tMs,
                type = DetectorEventType.MONITOR_END,
                note = EndReason.TIMEOUT,
            )
            return TickOutcome(events = events, endRequested = true, endReason = EndReason.TIMEOUT)
        }

        val raw = detector.onFeatures(f)
        for (e in raw) {
            when (e.type) {
                DetectorEventType.WARMUP_DONE -> {
                    events += e
                    // k == 1 特例：**站台上开始**时，预热结束即提醒「下一站就是目的站」
                    // （此时车还没开，下一站确实是目的站）。
                    // 若为「车上中途开始」，下一站就是目的地这一事实依然成立 —— 同样提前提醒。
                    // 两种姿势都发：这是安全冗余（到站还会再提醒一次，去重由 alertPrevSent 保证）。
                    if (route.stopCount == 1) {
                        emitPreAlert(f.tMs)?.let { events += it }
                    }
                }

                DetectorEventType.STATION_ARRIVED -> events += translateArrival(e, f.tMs)

                DetectorEventType.DEPART -> {
                    lastDwellS = e.dwellS
                    events += e
                }

                DetectorEventType.DATA_GAP -> {
                    events += e
                }

                DetectorEventType.DATA_RESUME -> events += e

                else -> events += e
            }
        }

        // ---------- v4 融合层（影子模式下两个开关皆 false，此段零行为）----------
        val dtSec = if (prevTickMs == 0L) 0.0 else (f.tMs - prevTickMs).coerceAtLeast(0L) / 1000.0
        prevTickMs = f.tMs
        if (config.cellZoneScoutEnabled && zoneTracker.active) {
            val zone = zoneTracker.confirmedRange
            val expected = stationCount + 1
            // 侦察补检：区内放宽静稳跨度（原始 vibStopTh 形不成跨度的晨高峰漏检站）
            if (zone != null && zone.first >= expected &&
                expected <= route.stopCount &&
                detector.state != DetectorState.STOPPED
            ) {
                if (f.vib < config.zoneStillVibTh) {
                    if (zoneStillStartMs == 0L) zoneStillStartMs = f.tMs
                    zoneStillLastMs = f.tMs
                    if ((f.tMs - zoneStillStartMs) / 1000.0 >= config.zoneStillConfirmSec) {
                        zoneStillStartMs = 0L
                        events += translateArrival(
                            DetectorEvent(
                                tMs = f.tMs,
                                type = DetectorEventType.STATION_ARRIVED,
                                note = "zone_still vib=%.3f".format(f.vib),
                            ),
                            f.tMs,
                        )
                    }
                } else if (zoneStillStartMs != 0L &&
                    f.tMs - zoneStillLastMs > (config.zoneStillTolSec * 1000).toLong()
                ) {
                    zoneStillStartMs = 0L
                }
            } else {
                zoneStillStartMs = 0L
            }
            // 目的站兜底：进目的站区后 zoneArrivalFallbackSec 仍无静稳计数（用户走动噪声
            // 远高于任何 vib 阈值，如玉双路停站窗 0.66）→ 定时产生到站候选。
            // 到站提醒宁可提前不可漏发；提前量 = 进区早于停稳的 0~58 s。
            if (destZoneAtMs != 0L && !arrived && stationCount < route.stopCount &&
                f.tMs - destZoneAtMs >= (config.zoneArrivalFallbackSec * 1000).toLong()
            ) {
                destZoneAtMs = 0L
                events += translateArrival(
                    DetectorEvent(
                        tMs = f.tMs,
                        type = DetectorEventType.STATION_ARRIVED,
                        note = "zone_dest_fallback",
                    ),
                    f.tMs,
                    bypassInterval = true,
                )
            }
        } else {
            zoneStillStartMs = 0L
        }

        // ---- 到站后延时结束 ----
        var endRequested = false
        var endReason: String? = null
        val endAt = scheduledEndAtMs
        if (endAt != null && f.tMs >= endAt) {
            detector.finish(f.tMs, EndReason.ARRIVED)
            running = false
            endRequested = true
            endReason = EndReason.ARRIVED
            events += DetectorEvent(
                tMs = f.tMs,
                type = DetectorEventType.MONITOR_END,
                note = EndReason.ARRIVED,
            )
        }
        return TickOutcome(
            events = events,
            endRequested = endRequested,
            endReason = endReason,
            scheduledEndAtMs = scheduledEndAtMs,
        )
    }

    /**
     * 把物理到站候选翻译成计数与提醒（首站忽略 / 站区门 / 间隔门槛 / 提醒去重）。
     *
     * v4 站区门（`cellZoneGateEnabled` 且映射有效时）：
     * - 站区不包含期望下一站 → ZONE_SUPPRESSED（区间信号停车 / 区外停顿免疫，不计数）；
     * - 站区首站位 > 期望位（前一站被漏检）→ **重同步**：候选直接对齐到站区首站位，
     *   防止一站漏检后期望位永久落后造成级联失步；
     * - `bypassInterval`：目的站兜底候选跳过最短间隔门槛（到站提醒宁早勿漏）。
     */
    private fun translateArrival(
        e: DetectorEvent,
        nowMs: Long,
        bypassInterval: Boolean = false,
    ): List<DetectorEvent> {
        val out = ArrayList<DetectorEvent>(2)

        // 首站忽略规则（按「开始姿势」区分，2026-09-25 实测修订）：
        // - 站台上开始（startedInMotion=false）且尚未起步 → 这次停稳就是上车站本身，忽略；
        // - 车上中途开始（startedInMotion=true）→ 没有「上车站停稳」可忽略，第一次停站即真实第 1 站。
        // 旧逻辑只看 hasRun，会把「中途开始」的第一个真实站吞掉（实测：1 站路程要摇两次才提醒）。
        if (!detector.hasRun && !detector.startedInMotion) {
            out += DetectorEvent(
                tMs = nowMs,
                type = DetectorEventType.FIRST_STOP_IGNORED,
                stationIndex = route.boardingIndex,
                stationName = route.boardingStation.name,
                intervalS = e.intervalS,
                // 透传检测层 note（vib=… / 通道 B 判据值）供离线分析
                note = listOfNotNull("boarding_station", e.note).joinToString(" "),
            )
            return out
        }

        // v4 站区门 + 期望站位解析（影子模式 = gate 关，targetPos 保持 stationCount+1）
        var targetPos = stationCount + 1
        if (config.cellZoneGateEnabled && zoneTracker.active) {
            val zone = zoneTracker.confirmedRange
            when {
                // 区外（未命中任何站区）：区间信号停车 / 上车站内停顿 → 拦截不计数
                zone == null -> {
                    out += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.ZONE_SUPPRESSED,
                        stationIndex = route.currentStationIndex(targetPos),
                        stationName = route.currentStation(targetPos).name,
                        note = "zone=none expected=$targetPos src=${e.note}",
                    )
                    return out
                }
                // 正常：站区包含期望下一站
                targetPos in zone -> Unit
                // 漏检重同步：站区首站位在期望位之后（中间站被漏检）→ 对齐到站区首站位
                zone.first > targetPos -> targetPos = zone.first
                // 站区落后于期望位（上一站小区滞留中的重复候选）→ 拦截
                else -> {
                    out += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.ZONE_SUPPRESSED,
                        stationIndex = route.currentStationIndex(targetPos),
                        stationName = route.currentStation(targetPos).name,
                        note = "zone=${zone.first}..${zone.last} expected=$targetPos src=${e.note}",
                    )
                    return out
                }
            }
        }

        // 两站最短间隔门槛（仅当已有计数时才比较；首个计数不受门槛约束）
        val intervalS = if (lastCountedMs == 0L) null else (nowMs - lastCountedMs) / 1000.0
        if (!bypassInterval && intervalS != null && intervalS < config.minStopIntervalSec) {
            out += DetectorEvent(
                tMs = nowMs,
                type = DetectorEventType.STOP_SUSPECT,
                stationIndex = route.currentStationIndex(stationCount),
                stationName = route.currentStation(stationCount).name,
                intervalS = intervalS,
                note = "interval<${config.minStopIntervalSec}s",
            )
            return out
        }

        // 计数（重同步时直接跳到目标位，跳过的站并入 note 供离线分析）
        val beforeCount = stationCount
        stationCount = targetPos
        lastCountedMs = nowMs
        val idx = route.currentStationIndex(stationCount)
        val resyncNote = if (targetPos > beforeCount + 1) " resync=${beforeCount + 1}..${targetPos - 1}_skipped" else null
        out += DetectorEvent(
            tMs = nowMs,
            type = DetectorEventType.STATION_ARRIVED,
            stationIndex = idx,
            stationName = route.stationNameAt(idx),
            intervalS = intervalS,
            dwellS = null,
            // 保留检测层 note（vib=… / still_no_brake / zone_still）供离线分析缓刹 / 有制动两类到站
            note = listOfNotNull(e.note, resyncNote, "n=$stationCount/k=${route.stopCount}").joinToString(" "),
        )

        val k = route.stopCount
        when {
            stationCount == k -> {
                if (!alertArrivedSent) {
                    alertArrivedSent = true
                    arrived = true
                    scheduledEndAtMs = nowMs + (config.endGraceSec * 1000).toLong()
                    out += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.ALERT_ARRIVED,
                        stationIndex = route.destinationIndex,
                        stationName = route.destinationStation.name,
                        note = "end_at_${scheduledEndAtMs}",
                    )
                    if (!alertPrevSent && k > 1) {
                        // 极端情况：直接跳过了 D−1 的提醒（例如中间站被门槛过滤）
                        alertPrevSent = true
                    }
                }
            }

            stationCount == k - 1 -> {
                emitPreAlert(nowMs)?.let { out += it }
            }

            stationCount > k -> {
                if (!overshootSent) {
                    overshootSent = true
                    out += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.OVERSHOOT,
                        stationIndex = route.destinationIndex,
                        stationName = route.destinationStation.name,
                        note = "n=$stationCount>k=$k",
                    )
                }
            }
        }
        return out
    }

    private fun emitPreAlert(nowMs: Long): DetectorEvent? {
        if (alertPrevSent) return null
        alertPrevSent = true
        return DetectorEvent(
            tMs = nowMs,
            type = DetectorEventType.ALERT_PREV,
            stationIndex = route.destinationIndex,
            stationName = route.destinationStation.name,
            note = "next_is_destination",
        )
    }

    /** 站区显示名：单站返回站名；共享小区的多站段返回「A~B」 */
    private fun zoneDisplayName(range: IntRange): String? = when {
        range.first == range.last -> route.currentStation(range.first).name
        else -> "${route.currentStation(range.first).name}~${route.currentStation(range.last).name}"
    }

    /** 手动纠错：我已多过一站（计数 +by） */
    fun correctUp(nowMs: Long, by: Int = 1): TickOutcome {
        if (!running) return TickOutcome()
        stationCount += by
        // 纠错代表一次真实到站：间隔门槛以纠错时刻为锚，否则纠错后的自动到站可能被误拦
        lastCountedMs = nowMs
        val events = ArrayList<DetectorEvent>(2)
        events += DetectorEvent(
            tMs = nowMs,
            type = DetectorEventType.CORRECTION_UP,
            stationIndex = route.currentStationIndex(stationCount),
            stationName = route.currentStation(stationCount).name,
            note = "by=$by n=$stationCount",
        )
        // 纠错补发提醒（2026-09-28 实测缺口：纠错到 k−1 / k 时用户全程收不到任何提醒）：
        // 纠错语义与自动到站一致 —— 到 k−1 发 D−1、到 k 发到达提醒并排定自动结束、超 k 发坐过站。
        val k = route.stopCount
        when {
            stationCount == k -> {
                if (!alertArrivedSent) {
                    alertArrivedSent = true
                    arrived = true
                    scheduledEndAtMs = nowMs + (config.endGraceSec * 1000).toLong()
                    events += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.ALERT_ARRIVED,
                        stationIndex = route.destinationIndex,
                        stationName = route.destinationStation.name,
                        note = "end_at_${scheduledEndAtMs}",
                    )
                    if (!alertPrevSent && k > 1) alertPrevSent = true
                }
            }

            stationCount == k - 1 -> emitPreAlert(nowMs)?.let { events += it }

            stationCount > k -> {
                if (!overshootSent) {
                    overshootSent = true
                    events += DetectorEvent(
                        tMs = nowMs,
                        type = DetectorEventType.OVERSHOOT,
                        stationIndex = route.destinationIndex,
                        stationName = route.destinationStation.name,
                        note = "corrected",
                    )
                }
            }
        }
        return TickOutcome(events = events, scheduledEndAtMs = scheduledEndAtMs)
    }

    /** 手动纠错：少算了一站（计数 −by，不小于 0） */
    fun correctDown(nowMs: Long, by: Int = 1): TickOutcome {
        if (!running) return TickOutcome()
        val before = stationCount
        stationCount = (stationCount - by).coerceAtLeast(0)
        lastCountedMs = nowMs
        val events = ArrayList<DetectorEvent>(1)
        events += DetectorEvent(
            tMs = nowMs,
            type = DetectorEventType.CORRECTION_DOWN,
            stationIndex = route.currentStationIndex(stationCount),
            stationName = route.currentStation(stationCount).name,
            note = "by=$by $before->$stationCount",
        )
        if (stationCount < route.stopCount) {
            // 退回未到站状态：撤销自动结束并允许后续提醒重新计算
            arrived = false
            scheduledEndAtMs = null
            if (stationCount < route.stopCount) alertArrivedSent = false
            if (stationCount < route.stopCount - 1) alertPrevSent = false
        }
        return TickOutcome(events = events, scheduledEndAtMs = scheduledEndAtMs)
    }

    /** 手动结束 */
    fun finish(nowMs: Long, reason: String = EndReason.MANUAL): TickOutcome {
        if (!running) return TickOutcome()
        running = false
        detector.finish(nowMs, reason)
        return TickOutcome(
            events = listOf(DetectorEvent(tMs = nowMs, type = DetectorEventType.MONITOR_END, note = reason)),
            endRequested = true,
            endReason = reason,
        )
    }

    /** 当前 UI 快照（不含计时器，elapsed 由调用方按 nowMs 补） */
    fun snapshot(nowMs: Long): com.metrostop.reminder.core.model.MonitorUiState {
        val n = stationCount
        return com.metrostop.reminder.core.model.MonitorUiState(
            running = running,
            state = detector.state,
            lineName = route.lineName,
            directionName = route.directionName,
            boardingStation = route.boardingStation.name,
            destinationStation = route.destinationStation.name,
            stationCount = n,
            currentStation = route.currentStation(n).name,
            nextStation = route.nextStation(n).name,
            remaining = route.remaining(n),
            totalStops = route.stopCount,
            elapsedSec = if (startedAtMs == 0L) 0.0 else (nowMs - startedAtMs) / 1000.0,
            dataGap = detector.state == com.metrostop.reminder.core.model.DetectorState.INTERRUPTED,
            endScheduled = scheduledEndAtMs != null,
            arrived = arrived,
            // v4：当前所在站区（非空时 UI 显示「进站中·<站区>」，比 IMU 状态机更贴近事实）
            zoneStation = zoneTracker.confirmedRange?.let { zoneDisplayName(it) },
        )
    }

    val scheduledEndAt: Long? get() = scheduledEndAtMs
    val lastDwellSec: Double? get() = lastDwellS
}
