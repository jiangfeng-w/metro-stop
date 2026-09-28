package com.metrostop.reminder.core.fsm

import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
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
 */
class MonitorSession(
    val route: RouteSpec,
    val config: TuningConfig = TuningConfig.Default,
) {
    private val detector = StationStopDetector(config)

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
        val events = ArrayList<DetectorEvent>(detector.start(nowMs))
        return TickOutcome(events = events)
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

    /** 把物理到站翻译成计数与提醒（首站忽略 / 间隔门槛 / 提醒去重） */
    private fun translateArrival(e: DetectorEvent, nowMs: Long): List<DetectorEvent> {
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
                note = "boarding_station",
            )
            return out
        }

        // 两站最短间隔门槛（仅当已有计数时才比较；首个计数不受门槛约束）
        val intervalS = if (lastCountedMs == 0L) null else (nowMs - lastCountedMs) / 1000.0
        if (intervalS != null && intervalS < config.minStopIntervalSec) {
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

        // 计数
        stationCount++
        lastCountedMs = nowMs
        val idx = route.currentStationIndex(stationCount)
        out += DetectorEvent(
            tMs = nowMs,
            type = DetectorEventType.STATION_ARRIVED,
            stationIndex = idx,
            stationName = route.stationNameAt(idx),
            intervalS = intervalS,
            dwellS = null,
            // 保留检测层 note（vib=… / still_no_brake）供离线分析缓刹 / 有制动两类到站
            note = listOfNotNull(e.note, "n=$stationCount/k=${route.stopCount}").joinToString(" "),
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
        )
    }

    val scheduledEndAt: Long? get() = scheduledEndAtMs
    val lastDwellSec: Double? get() = lastDwellS
}
