package com.metrostop.reminder.core.fsm

import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.DetectorState
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.TuningConfig

/**
 * 停站识别状态机 v2（2026-09-28 早高峰真实通勤数据重标，分析见交接文档）。
 *
 * 时间基准：**只用样本自带的 `tMs`**（单调时钟），不读系统时钟 —— 因此 CSV 回放与现场行为完全一致。
 *
 * v2 相对 v1 的变化（全部由 2026-09-28 真实 6/4 号线数据裁决）：
 * 1. **静稳从「累计」改为「跨度 + 容差」**：`vib < vibStopTh` 的连续跨度（允许 ≤[TuningConfig.stillTolSec]
 *    毛刺）达 [TuningConfig.stillConfirmSec] 才算停稳 —— 累计规则会被巡航平滑段的
 *    反复短暂下探在区间内凑满 8 s 而误报；
 * 2. **乘车证据门**：到站判定（制动路径与静稳路径）统一要求最近
 *    [TuningConfig.rideBandWindowSec] 内存在 ≥[TuningConfig.rideBandMinSec] 的
 *    「vib ∈ [rideBandLo, rideBandHi]」连续段 —— 拦截站台静立等车、走路后站住两类结构性误报；
 * 3. **巡航直置 hasRun**：巡航振动持续 ≥[TuningConfig.cruiseRunConfirmSec] 即认定「确已在乘车」，
 *    不再依赖「STOPPED→DEPART」才置 hasRun（站台开始→上车的首段巡航没有这个过程）；
 * 4. **STOPPED 内重新静稳也可到站**：到站不再依赖 DEPART 重锚定（v1 的 vibRunTh=0.25 在真实
 *    车厢不可达 → 计数一次后状态机卡死在 STOPPED，12 站漏 10 站）；
 * 5. **DEPART 守卫**：起步确认 [TuningConfig.departConfirmSec]=10 s + 停站至少
 *    [TuningConfig.minDwellBeforeDepartSec] —— 门开人群噪声的 vib 连续段不再误判起步。
 *
 * 边沿触发的事件各只产生一次，便于通知去重。
 */
class StationStopDetector(private val config: TuningConfig) {

    var state: DetectorState = DetectorState.IDLE
        private set

    /** 已确认到站数（不含被忽略的首站 / 疑似站） */
    var stationCount: Int = 0
        private set

    /** 是否已经发生过「起步」（用于判断第一个到站是否为上车站本身） */
    var hasRun: Boolean = false
        private set

    /**
     * 监测开始时列车是否**已在行驶**（预热期内检测到持续振动）。
     *
     * 与 [hasRun] 共同决定首站是否忽略：
     * - 站台上开始（`startedInMotion=false`）→ 第一次停站 = 上车站本身，忽略；
     * - 车上中途开始（`startedInMotion=true`）→ 第一次停站 = 真实第 1 站，必须计数。
     */
    var startedInMotion: Boolean = false
        private set

    /** 上一次计数到站的时刻（用于 minStopIntervalSec 门槛） */
    var lastStationMs: Long = 0L
        private set

    /** 采样缺口累计次数（异常通知用） */
    var dataGapCount: Int = 0
        private set

    /** 最近一次已确认到站的时刻（UI 显示 dwell / DEPART 停站守卫用） */
    var lastArrivalMs: Long = 0L
        private set

    private var startMs = 0L
    private var warmupUntilMs = 0L
    private var prevSampleMs = 0L

    private var brakeForSec = 0.0
    private var brakeReleaseForSec = 0.0
    private var runForSec = 0.0
    private var brakingEnteredMs = 0L

    /** 本次停站开始（静稳起点）的时刻，用于 dwell 统计 */
    private var dwellStartMs = 0L
    private var interruptedPrevState = DetectorState.WARMUP

    // ---------- v2: 静稳跨度（stillStartMs == 0 表示跨度未开启） ----------
    private var stillStartMs = 0L
    private var lastStillMs = 0L

    // ---------- v2: 巡航行驶跨度（runSpanStartMs == 0 表示未开启；用于巡航直置 hasRun） ----------
    private var runSpanStartMs = 0L
    private var runSpanLastMs = 0L

    // ---------- v2: 乘车证据带（已闭合段 + 进行中段） ----------
    private var bandStartMs = 0L
    private var bandLastMs = 0L
    private val bandSegStarts = ArrayList<Long>(8)
    private val bandSegEnds = ArrayList<Long>(8)

    /** 上次计数到站后振动是否回升过（restill 的前置条件：没有回升就没有「新的一站」） */
    private var vibRisenSinceArrival = false

    /** 开始监测：进入预热；startGraceSec 内不判定 */
    fun start(nowMs: Long): List<DetectorEvent> {
        state = DetectorState.WARMUP
        stationCount = 0
        hasRun = false
        startedInMotion = false
        lastStationMs = 0L
        lastArrivalMs = 0L
        dataGapCount = 0
        brakeForSec = 0.0
        brakeReleaseForSec = 0.0
        runForSec = 0.0
        resetSpans()
        startMs = nowMs
        prevSampleMs = nowMs
        warmupUntilMs = nowMs + (config.startGraceSec * 1000).toLong()
        return listOf(DetectorEvent(tMs = nowMs, type = DetectorEventType.MONITOR_START))
    }

    /** 结束监测（手动 / 到站延时 / 超时兜底） */
    fun finish(nowMs: Long, reason: String, note: String? = null): List<DetectorEvent> {
        state = DetectorState.FINISHED
        return listOf(
            DetectorEvent(
                tMs = nowMs,
                type = DetectorEventType.MONITOR_END,
                stationIndex = null,
                note = note ?: reason,
            ),
        )
    }

    /** 断流恢复后的重新预热 */
    private fun resumeWarmup(nowMs: Long) {
        state = DetectorState.WARMUP
        warmupUntilMs = nowMs + (config.warmupResumeSec * 1000).toLong()
        brakeForSec = 0.0
        brakeReleaseForSec = 0.0
        runForSec = 0.0
        resetSpans()
    }

    private fun resetSpans() {
        stillStartMs = 0L
        lastStillMs = 0L
        runSpanStartMs = 0L
        runSpanLastMs = 0L
        bandStartMs = 0L
        bandLastMs = 0L
        bandSegStarts.clear()
        bandSegEnds.clear()
        vibRisenSinceArrival = false
    }

    /**
     * 「行驶形态」判据（用于 startedInMotion / 巡航置 hasRun）：
     * - 证据门开启：vib 落在乘车证据带内（真实车厢巡航量级）——
     *   裸 vibRunTh(0.10) 会把静坐时的手持 fidget（vib 0.3+ 持续 12 s+）误判成「确已在乘车」，
     *   导致上车站被当作第 1 站计数（2026-09-28 3 站手摇资产回放实测暴露）；
     * - 证据门关闭（手摇回归资产分层）：退回 v1 语义 vib > vibRunTh。
     */
    private fun rideLike(vib: Float): Boolean =
        if (config.evidenceGateEnabled) {
            vib >= config.rideBandLo && vib <= config.rideBandHi
        } else {
            vib > config.vibRunTh
        }

    /**
     * 处理一条特征样本，返回本样本触发的全部事件（可能为空）。
     */
    fun onFeatures(f: Features): List<DetectorEvent> {
        if (state == DetectorState.IDLE || state == DetectorState.FINISHED) return emptyList()
        val out = ArrayList<DetectorEvent>(2)
        val now = f.tMs
        val dtMs = if (prevSampleMs == 0L) 0L else now - prevSampleMs
        prevSampleMs = now
        val dtSec = (dtMs.coerceAtLeast(0L)) / 1000.0

        // ---------- 断流检测 ----------
        if (state == DetectorState.INTERRUPTED) {
            if (now >= warmupUntilMs) {
                out += DetectorEvent(tMs = now, type = DetectorEventType.DATA_RESUME)
                state = DetectorState.CRUISE
            }
            return out
        }
        if (dtMs > (config.dataGapSec * 1000).toLong()) {
            dataGapCount++
            interruptedPrevState = state
            out += DetectorEvent(
                tMs = now,
                type = DetectorEventType.DATA_GAP,
                note = "gapMs=$dtMs from=${interruptedPrevState.name}",
            )
            state = DetectorState.INTERRUPTED
            warmupUntilMs = now + (config.warmupResumeSec * 1000).toLong()
            return out
        }

        // ---------- 预热 ----------
        if (state == DetectorState.WARMUP) {
            // 预热期内观察振动：持续在动 → 判定「开始时列车已在行驶」
            // （决定首站是否忽略，见 startedInMotion 注释）
            if (rideLike(f.vib)) {
                runForSec += dtSec
                if (runForSec >= config.startMovingConfirmSec) {
                    startedInMotion = true
                }
            } else {
                runForSec = 0.0
            }
            if (now >= warmupUntilMs) {
                out += DetectorEvent(
                    tMs = now,
                    type = DetectorEventType.WARMUP_DONE,
                    note = if (startedInMotion) "started_in_motion" else "started_at_platform",
                )
                state = DetectorState.CRUISE
                runForSec = 0.0
            }
            return out
        }

        // ---------- 特征累计（按秒，不按样本数）----------
        val braking = f.h > config.brakeAccelTh
        if (braking) {
            brakeForSec += dtSec
            brakeReleaseForSec = 0.0
        } else {
            brakeReleaseForSec += dtSec
            brakeForSec = 0.0
        }

        val running = f.vib > config.vibRunTh
        val still = f.vib < config.vibStopTh
        val stillBroke = updateStillSpan(now, f.vib)
        updateBand(now, f.vib)
        if (running || stillBroke) vibRisenSinceArrival = true

        when (state) {
            DetectorState.CRUISE -> {
                // 巡航直置 hasRun：站台开始→上车的首段巡航没有 STOPPED→DEPART 过程，
                // 「确已在乘车」必须能由巡航自身证据产生（v2 变化 3）。
                // 判据：vib 高于停稳阈值（gate-ON）／运行阈值（gate-OFF 兼容手摇资产）的
                // 连续跨度（容忍 stillTolSec 毛刺）≥ cruiseRunConfirmSec。
                // 不用证据带：真实短区间频繁出带会吞掉首个真实站（2026-09-28 实测复现）。
                val runEvidence = if (config.evidenceGateEnabled) f.vib > config.vibStopTh else f.vib > config.vibRunTh
                if (runEvidence) {
                    if (runSpanStartMs == 0L) runSpanStartMs = now
                    runSpanLastMs = now
                    if (now - runSpanStartMs >= (config.cruiseRunConfirmSec * 1000).toLong()) hasRun = true
                } else if (runSpanStartMs != 0L && now - runSpanLastMs > (config.stillTolSec * 1000).toLong()) {
                    runSpanStartMs = 0L
                }
                when {
                    braking && brakeForSec >= config.brakeMinSec -> {
                        state = DetectorState.BRAKING
                        brakingEnteredMs = now
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_START, note = "h=%.3f".format(f.h))
                    }
                    stillSpanSec(now) >= config.stillConfirmSec -> {
                        // 无制动特征的到站（缓刹 / 区间停车）：走同一计数出口，note 标记供离线分析
                        out += DetectorEvent(tMs = now, type = DetectorEventType.STOPPING, note = "still_no_brake vib=%.3f".format(f.vib))
                        out += attemptStop(now, note = "still_no_brake")
                    }
                }
            }

            DetectorState.BRAKING -> {
                val brakeElapsedSec = (now - brakingEnteredMs) / 1000.0
                when {
                    // ① 停稳迹象优先：振动跌落 → STOPPING
                    still -> {
                        state = DetectorState.STOPPING
                        out += DetectorEvent(tMs = now, type = DetectorEventType.STOPPING, note = "vib=%.3f".format(f.vib))
                    }
                    // ② 制动超时（曲线 / 缓行）
                    brakeElapsedSec > config.brakeMaxSec -> {
                        state = DetectorState.CRUISE
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "timeout")
                    }
                    // ③ 制动释放（H 回落）
                    brakeReleaseForSec >= config.brakeReleaseSec -> {
                        state = DetectorState.CRUISE
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "released")
                    }
                }
            }

            DetectorState.STOPPING -> {
                when {
                    // ① 静稳跨度已确认 → 判定到站（证据门统一在 attemptStop）
                    stillSpanSec(now) >= config.stillConfirmSec -> {
                        out += attemptStop(now, note = "vib=%.3f".format(f.vib))
                    }
                    // ② 振动回升（直接 running，或静稳因超容差中断）→ 放弃
                    running || stillStartMs == 0L -> {
                        state = DetectorState.CRUISE
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "vib_rise")
                    }
                    // ③ 制动超时（曲线 / 缓行）
                    brakeElapsedSec(now) > config.brakeMaxSec -> {
                        state = DetectorState.CRUISE
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "timeout")
                    }
                    // ④ 制动释放（H 回落）
                    brakeReleaseForSec >= config.brakeReleaseSec -> {
                        state = DetectorState.CRUISE
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "released")
                    }
                }
            }

            DetectorState.STOPPED -> {
                if (running) {
                    runForSec += dtSec
                    if (runForSec >= config.departConfirmSec &&
                        now - lastArrivalMs >= (config.minDwellBeforeDepartSec * 1000).toLong()
                    ) {
                        val dwellS = (now - dwellStartMs) / 1000.0
                        out += DetectorEvent(
                            tMs = now,
                            type = DetectorEventType.DEPART,
                            dwellS = dwellS,
                        )
                        hasRun = true
                        runForSec = 0.0
                        stillStartMs = 0L
                        state = DetectorState.CRUISE
                    }
                } else {
                    runForSec = 0.0
                }
                // v2 变化 4：STOPPED 内重新形成的静稳跨度同样触发到站判定 ——
                // 相邻站 / 漏检站不依赖 DEPART 重锚定（v1 卡死根因）。
                // 前置：上次计数后振动必须回升过（vibRisenSinceArrival）——
                // 同一次停站的静稳里不允许 restill 重复触发（否则静坐期每 8 s 刷一条 SUSPECT）。
                if (state == DetectorState.STOPPED && vibRisenSinceArrival &&
                    stillSpanSec(now) >= config.stillConfirmSec
                ) {
                    out += attemptStop(now, note = "restill")
                }
            }

            else -> Unit
        }
        return out
    }

    private fun brakeElapsedSec(now: Long): Double = (now - brakingEnteredMs) / 1000.0

    /** 静稳跨度（秒）；未开启返回 0 */
    private fun stillSpanSec(now: Long): Double =
        if (stillStartMs == 0L) 0.0 else (now - stillStartMs) / 1000.0

    /** 静稳跨度维护：vib < vibStopTh 开启 / 延伸；≥ 阈值时容忍 stillTolSec 毛刺，超时中断。返回本次是否发生中断 */
    private fun updateStillSpan(now: Long, vib: Float): Boolean {
        if (vib < config.vibStopTh) {
            if (stillStartMs == 0L) stillStartMs = now
            lastStillMs = now
            return false
        }
        if (stillStartMs != 0L && now - lastStillMs > (config.stillTolSec * 1000).toLong()) {
            stillStartMs = 0L
            return true
        }
        return false
    }

    /** 乘车证据带维护：vib ∈ [rideBandLo, rideBandHi] 开启 / 延伸段；出带超容差闭合；滑窗裁剪 */
    private fun updateBand(now: Long, vib: Float) {
        if (vib >= config.rideBandLo && vib <= config.rideBandHi) {
            if (bandStartMs == 0L) bandStartMs = now
            bandLastMs = now
        } else if (bandStartMs != 0L && now - bandLastMs > (config.stillTolSec * 1000).toLong()) {
            bandSegStarts += bandStartMs
            bandSegEnds += bandLastMs
            bandStartMs = 0L
        }
        val winStartMs = now - (config.rideBandWindowSec * 1000).toLong()
        while (bandSegStarts.isNotEmpty() && bandSegEnds.first() < winStartMs) {
            bandSegStarts.removeAt(0)
            bandSegEnds.removeAt(0)
        }
    }

    /** 最近 rideBandWindowSec 内是否存在 ≥ rideBandMinSec 的连续带内段（含进行中段） */
    private fun hasRideEvidence(now: Long): Boolean {
        val minMs = (config.rideBandMinSec * 1000).toLong()
        val winStartMs = now - (config.rideBandWindowSec * 1000).toLong()
        if (bandStartMs != 0L && bandLastMs >= winStartMs && bandLastMs - bandStartMs >= minMs) return true
        for (i in bandSegStarts.indices) {
            if (bandSegEnds[i] >= winStartMs && bandSegEnds[i] - bandSegStarts[i] >= minMs) return true
        }
        return false
    }

    /**
     * 到站判定（计数点）：静稳跨度确认时调用（CRUISE / STOPPING / STOPPED-restill 三条入口共用）。
     *
     * - 证据门拦截 → EVIDENCE_BLOCKED，状态不变（站台静立等非乘车停顿不进入停站流程）；
     * - 通过 → STATION_ARRIVED 原始事件 + 进入 STOPPED（首站忽略 / 间隔门槛由 MonitorSession 决定）。
     * 无论结果，静稳跨度清零：重新形成跨度才能重试。
     */
    private fun attemptStop(now: Long, note: String): DetectorEvent {
        val spanStartMs = stillStartMs
        stillStartMs = 0L
        if (config.evidenceGateEnabled && !hasRideEvidence(now)) {
            return DetectorEvent(tMs = now, type = DetectorEventType.EVIDENCE_BLOCKED, note = "no_ride_band src=$note")
        }
        state = DetectorState.STOPPED
        runForSec = 0.0
        vibRisenSinceArrival = false
        dwellStartMs = spanStartMs
        lastArrivalMs = now
        val intervalS = if (lastStationMs == 0L) null else (now - lastStationMs) / 1000.0
        lastStationMs = now
        return DetectorEvent(
            tMs = now,
            type = DetectorEventType.STATION_ARRIVED,
            dwellS = null,
            intervalS = intervalS,
            note = note,
        )
    }

    /** 已运行时长（秒），到站后延时结束用 */
    fun elapsedSec(nowMs: Long): Double = (nowMs - startMs) / 1000.0

    fun currentDwellSec(nowMs: Long): Double =
        if (state == DetectorState.STOPPED) (nowMs - dwellStartMs) / 1000.0 else 0.0

    companion object {
        fun endReasonNote(reason: String): String = when (reason) {
            EndReason.MANUAL -> "手动结束"
            EndReason.ARRIVED -> "已到达目的站"
            EndReason.TIMEOUT -> "超过最长监测时长"
            else -> reason
        }
    }
}
