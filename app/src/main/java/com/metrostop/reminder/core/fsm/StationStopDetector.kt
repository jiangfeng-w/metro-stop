package com.metrostop.reminder.core.fsm

import com.metrostop.reminder.core.feature.GaitGate
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

    // ---------- v3: 预热静稳否决（startedInMotion 的「站台开始」铁证） ----------
    private var warmupStillForSec = 0.0
    private var startInMotionVetoed = false

    // ---------- v2: 乘车证据带（已闭合段 + 进行中段） ----------
    private var bandStartMs = 0L
    private var bandLastMs = 0L
    private val bandSegStarts = ArrayList<Long>(8)
    private val bandSegEnds = ArrayList<Long>(8)

    // ---------- v3: 步态门 + 通道 B（站姿乘车）的 1 s 桶统计 ----------
    private val gaitGate = GaitGate(config)

    /** 通道 B 滑窗桶：Triple(秒序号, 本秒 vib 带内, 本秒非步行) */
    private val rideShapeBuckets = ArrayDeque<Triple<Long, Boolean, Boolean>>(160)

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
        gaitGate.reset()
        rideShapeBuckets.clear()
        hSecBuckets.clear()
        hAcc = 0f
        hAccN = 0
        hAccSec = 0L
        secAcc = 0L to Triple(0f, 0, false)
        warmupStillForSec = 0.0
        startInMotionVetoed = false
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
        gaitGate.reset()
        rideShapeBuckets.clear()
        hSecBuckets.clear()
        hAcc = 0f
        hAccN = 0
        hAccSec = 0L
        secAcc = 0L to Triple(0f, 0, false)
        warmupStillForSec = 0.0
        startInMotionVetoed = false
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
            // v3 终案（2026-09-28 早/晚通勤实测裁决）：**静稳否决**——预热内出现
            // ≥[TuningConfig.warmupStillVetoSec] 连续静稳（vib < vibStopTh）即证明「还没上车」，
            // 一票否决 startedInMotion。依据：站台开始早通勤预热 0-20s vib p50=0.077、大量
            // 静稳秒；中途上车晚通勤 0-20s 全 >0.085 无静稳。而站台进站前的人群/结构振动会
            // 凑满 startMovingConfirmSec 把站台开始误判成「车上中途开始」（早通勤实测
            // WARMUP_DONE=started_in_motion 但人在站台）。冲高判据（vib>startSurgeTh）不可用于
            // 此处：晚通勤预热起步冲高只有 0.386~0.425 < 0.45，会吞掉前 5 站（实测）。
            if (f.vib < config.vibStopTh) {
                warmupStillForSec += dtSec
                if (warmupStillForSec >= config.warmupStillVetoSec) {
                    startInMotionVetoed = true
                    startedInMotion = false
                }
            } else {
                warmupStillForSec = 0.0
            }
            if (rideLike(f.vib)) {
                runForSec += dtSec
                if (runForSec >= config.startMovingConfirmSec && !startInMotionVetoed) {
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

        // ---------- v3: 步态门 + 通道 B 滑桶 ----------
        // 步态门（需求 4.1）：走路样本**不累计乘车证据带**（防走路段污染带证据；
        // 走路本身 vib 高不会形成带内段，但站台走路—站住的过渡抖动会混入）。
        val walking = gaitGate.process(now, f.gyroMag)
        if (!walking) updateBand(now, f.vib)
        updateRideShapeBuckets(now, f.vib, walking)
        updateHSecBuckets(now, f.h)
        if (running || stillBroke) vibRisenSinceArrival = true

        when (state) {
            DetectorState.CRUISE -> {
                // 巡航直置 hasRun：站台开始→上车的首段巡航没有 STOPPED→DEPART 过程，
                // 「确已在乘车」必须能由巡航自身证据产生（v2 变化 3）。
                // 判据：vib 高于停稳阈值（gate-ON）／运行阈值（gate-OFF 兼容手摇资产）的
                // 连续跨度（容忍 stillTolSec 毛刺）≥ cruiseRunConfirmSec。
                // 不用证据带：真实短区间频繁出带会吞掉首个真实站（2026-09-28 实测复现）。
                // v3 说明：曾试过加「跨度内起步冲高」附加条件防站台误置位，实测否决——
                // 0.45 阈值修好早通勤却吞掉晚通勤起步（0.386~0.425 < 0.45），0.35 又锁死
                // 早通勤坐姿起步（仅 0.10~0.24）；且站台误置 hasRun 的后果已被静稳否决
                // （startedInMotion）+ 通道 B 证据门兜住，保持 v2 纯跨度语义。
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
        if (hasBandEvidence(now)) return true
        // v3 通道 B：站姿乘车——带内占比 + 非步行占比双门槛（抗 fidget 碎片化）
        if (hasRideStandEvidence(now)) return true
        return false
    }

    /** 通道 A：vib 带内连续段（v2 原逻辑，独立出便于诊断与组合判定） */
    private fun hasBandEvidence(now: Long): Boolean {
        val minMs = (config.rideBandMinSec * 1000).toLong()
        val winStartMs = now - (config.rideBandWindowSec * 1000).toLong()
        if (bandStartMs != 0L && bandLastMs >= winStartMs && bandLastMs - bandStartMs >= minMs) return true
        for (i in bandSegStarts.indices) {
            if (bandSegEnds[i] >= winStartMs && bandSegEnds[i] - bandSegStarts[i] >= minMs) return true
        }
        return false
    }

    /**
     * 通道 B：站姿乘车（占比 + 列车动态组合判据）。
     *
     * 离线标定结论（2026-09-28 早/晚通勤数据，全量真值点验证）：
     * - 站姿乘车与站台静立在 vib 分布（p50 0.115 vs 0.106）与 gyro（0.083 vs 0.079）上**几乎同分布**，
     *   纯窗内统计量不可分——只能靠占比 + 物理形态组合；
     * - 主门槛：150 s 窗「vib 带内」占比 ≥[TuningConfig.rideStandBandPct]（48%）→ 放行；
     * - 次门槛：占比 ∈[45%, 48%) 且窗内「列车动态冲高」（H >[TuningConfig.hSurgeTh] 持续
     *   ≥[TuningConfig.hSurgeMinSec]）≥[TuningConfig.rideStandSurgeCount]（5 次）→ 放行；
     * - 满窗要求：桶数 <150（监测开始后 150 s 内）一律不过——否则首个窗占比虚高会误放行
     *   站台静立（早通勤 t+158.7 实测：139 桶时占比 49% 假阳性）；
     * - 校验（两批数据回放）：早通勤（坐姿）12/12 不受影响、站台静立全拦；晚通勤（站姿）
     *   真站放行 7 组、区间信号停车 t+440/827 被 45% 下限拦住（两者 v2 也拦不住，v2 现场
     *   靠用户纠错；此处 v3 已能拦掉这两组）。
     */
    private fun hasRideStandEvidence(now: Long): Boolean {
        val winStartSec = (now - (config.rideStandWindowSec * 1000).toLong()) / 1000
        var inBand = 0
        var total = 0
        for ((sec, band, _w) in rideShapeBuckets) {
            if (sec < winStartSec) continue
            total++
            if (band) inBand++
        }
        // 满窗要求（v3 修订）：桶数不满 rideStandWindowSec 一律不过（B 通道本质需要 150 s 历史）。
        // 实测教训：早通勤 t+158.745（站台等车）时桶仅 139（WARMUP 20 s 后才开始累计），
        // 占比 49% 击穿 48 门槛被误放行；真站窗更大时站台占比只有 ~47%（分母含更多出带秒）。
        if (total < config.rideStandWindowSec.toInt()) return false
        val pct = inBand * 100.0 / total
        if (pct >= config.rideStandBandPct) return true
        if (pct < config.rideStandBandPctLo) return false
        return countSurges(winStartSec, now / 1000) >= config.rideStandSurgeCount
    }

    /** 窗内「列车动态冲高」段数：H 均值 >[TuningConfig.hSurgeTh] 连续 ≥[TuningConfig.hSurgeMinSec] 秒 */
    private fun countSurges(winStartSec: Long, endSec: Long): Int {
        var count = 0
        var run = 0
        var lastSec = winStartSec - 2 // 允许首个冲高从窗边界开始
        for ((sec, hMean) in hSecBuckets) {
            if (sec < winStartSec || sec > endSec) continue
            if (hMean > config.hSurgeTh) {
                if (run == 0 && sec - lastSec > 2) {
                    // 新段起点（与上一冲高断开 >2s）
                }
                run++
                lastSec = sec
            } else {
                if (run >= config.hSurgeMinSec) count++
                run = 0
            }
        }
        if (run >= config.hSurgeMinSec) count++
        return count
    }

    /**
     * 通道 B 滑桶维护：每样本把「本秒」聚合进桶（1 Hz 决策粒度）。
     *
     * ⚠️ 聚合语义必须与离线标定一致（**秒均值法**，非逐样本 OR）：
     * 站台静立段 vib 在 0.08~0.13 抖动，逐样本 OR 会把占比从 47% 虚高到 65%、
     * 击穿 50% 门槛（早通勤误放行已实测）。实现：秒内滚动累计均值，切秒时落桶。
     */
    private fun updateRideShapeBuckets(now: Long, vib: Float, walking: Boolean) {
        val sec = now / 1000
        if (secAcc.first == sec) {
            // 同一秒：滚动均值
            val (acc, n, _) = secAcc.second
            secAcc = secAcc.copy(second = Triple(acc + vib, n + 1, walking))
            return
        }
        // 切秒：上一秒落桶（首次调用 secAcc.first==0 直接初始化）
        if (secAcc.first != 0L) {
            val (acc, n, lastWalk) = secAcc.second
            if (n > 0) {
                val mean = acc / n
                rideShapeBuckets.addLast(Triple(secAcc.first, mean >= config.rideBandLo && mean <= config.rideBandHi, !lastWalk))
            }
        }
        secAcc = sec to Triple(vib, 1, walking)
        // 滑窗裁剪：只保留 rideStandWindowSec
        val winStartSec = (now - (config.rideStandWindowSec * 1000).toLong()) / 1000
        while (rideShapeBuckets.isNotEmpty() && rideShapeBuckets.first().first < winStartSec) {
            rideShapeBuckets.removeFirst()
        }
    }

    /** 当前秒聚合状态：(秒序号, (vib 累计, 样本数, 本秒最后一次 walking 输出)) */
    private var secAcc: Pair<Long, Triple<Float, Int, Boolean>> = 0L to Triple(0f, 0, false)

    /** H 秒均值桶维护（秒均值落桶，语义与 vib 桶一致） */
    private fun updateHSecBuckets(now: Long, h: Float) {
        val sec = now / 1000
        if (sec != hAccSec) {
            if (hAccN > 0) {
                hSecBuckets.addLast(hAccSec to hAcc / hAccN)
            }
            hAcc = 0f
            hAccN = 0
            hAccSec = sec
            val winStartSec = (now - (config.rideStandWindowSec * 1000).toLong()) / 1000
            while (hSecBuckets.isNotEmpty() && hSecBuckets.first().first < winStartSec) {
                hSecBuckets.removeFirst()
            }
        }
        hAcc += h
        hAccN++
    }

    /** H 秒均值桶（通道 B 冲高计数用）：秒 → H 均值 */
    private val hSecBuckets = ArrayDeque<Pair<Long, Float>>(160)
    private var hAcc = 0f
    private var hAccN = 0
    private var hAccSec = 0L

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
