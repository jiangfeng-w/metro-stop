package com.metrostop.reminder.core.fsm

import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.DetectorState
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.TuningConfig

/**
 * 停站识别状态机 v1（总纲 5.2）。
 *
 * 时间基准：**只用样本自带的 `tMs`**（单调时钟），不读系统时钟 —— 因此 CSV 回放与现场行为完全一致。
 * 边沿触发的事件（进入制动 / 到站 / 离站 / 断流）各只产生一次，便于通知去重。
 *
 * 到站判定三条件联合：① 持续制动（H > brakeAccelTh ≥ brakeMinSec）；
 * ② vib 跌落（< vibStopTh）；③ 静止累计 ≥ stillConfirmSec。
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

    /** 最近一次已确认到站的时刻（UI 显示 dwell 用） */
    var lastArrivalMs: Long = 0L
        private set

    private var startMs = 0L
    private var warmupUntilMs = 0L
    private var prevSampleMs = 0L

    private var brakeForSec = 0.0
    private var brakeReleaseForSec = 0.0
    private var stillForSec = 0.0
    private var runForSec = 0.0
    private var brakingEnteredMs = 0L
    private var stoppingEnteredMs = 0L

    /** 本次停站开始（进入 STOPPING）的时刻，用于 dwell 统计 */
    private var dwellStartMs = 0L
    private var interruptedPrevState = DetectorState.WARMUP

    /** 开始监测：进入预热；startGraceSec 内不判定 */
    fun start(nowMs: Long): List<DetectorEvent> {
        state = DetectorState.WARMUP
        stationCount = 0
        hasRun = false
        lastStationMs = 0L
        dataGapCount = 0
        brakeForSec = 0.0
        brakeReleaseForSec = 0.0
        stillForSec = 0.0
        runForSec = 0.0
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
        stillForSec = 0.0
        runForSec = 0.0
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
            if (f.vib > config.vibRunTh) {
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

        when (state) {
            DetectorState.CRUISE -> {
                if (runForSec > 0.0 || running) runForSec += dtSec
                // 巡航中也累计「静止」：列车缓刹 / 制动特征被滤波抹平时，H 可能一直不超阈值，
                // 但 vibr 跌落 + 持续静止仍是可靠的到站信号（实机回归：2026-09-25 shake 用例）。
                if (still) {
                    stillForSec += dtSec
                } else if (running) {
                    stillForSec = 0.0
                }
                when {
                    braking && brakeForSec >= config.brakeMinSec -> {
                        state = DetectorState.BRAKING
                        brakingEnteredMs = now
                        out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_START, note = "h=%.3f".format(f.h))
                    }
                    stillForSec >= config.stillConfirmSec -> {
                        // 无制动特征的到站（缓刹 / 区间停车）：走同一计数出口，note 标记供离线分析
                        out += DetectorEvent(tMs = now, type = DetectorEventType.STOPPING, note = "still_no_brake vib=%.3f".format(f.vib))
                        dwellStartMs = now - (config.stillConfirmSec * 1000).toLong()
                        out += detectStop(now, f, note = "still_no_brake")
                    }
                }
            }

            DetectorState.BRAKING -> {
                val brakeElapsedSec = (now - brakingEnteredMs) / 1000.0
                when {
                    // ① 停稳迹象优先：振动跌落 → STOPPING
                    still -> {
                        state = DetectorState.STOPPING
                        stoppingEnteredMs = now
                        dwellStartMs = now
                        stillForSec = 0.0
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
                if (still) {
                    stillForSec += dtSec
                } else if (running) {
                    // 停稳确认前振动又起来了 → 曲线 / 缓行，放弃
                    state = DetectorState.CRUISE
                    stillForSec = 0.0
                    out += DetectorEvent(tMs = now, type = DetectorEventType.BRAKE_ABORT, note = "vib_rise")
                }
                if (state == DetectorState.STOPPING && stillForSec >= config.stillConfirmSec) {
                    out += detectStop(now, f, note = "vib=%.3f".format(f.vib))
                }
            }

            DetectorState.STOPPED -> {
                if (running) {
                    runForSec += dtSec
                    if (runForSec >= config.departConfirmSec) {
                        val dwellS = (now - dwellStartMs) / 1000.0
                        out += DetectorEvent(
                            tMs = now,
                            type = DetectorEventType.DEPART,
                            dwellS = dwellS,
                        )
                        hasRun = true
                        runForSec = 0.0
                        state = DetectorState.CRUISE
                    }
                } else {
                    runForSec = 0.0
                }
            }

            else -> Unit
        }
        return out
    }

    /**
     * 判定到站（计数点）：STOPPING → STOPPED。
     *
     * 两条入口共用：① 制动后振动跌落；② 巡航中直接静止（缓刹 / 无制动特征，note 标记区分）。
     * 不在此处做计数门槛判断，交由 MonitorSession 按路线语义决定。
     */
    private fun detectStop(now: Long, f: Features, note: String): List<DetectorEvent> {
        state = DetectorState.STOPPED
        stillForSec = 0.0
        runForSec = 0.0
        val intervalS = if (lastStationMs == 0L) null else (now - lastStationMs) / 1000.0
        lastArrivalMs = now
        lastStationMs = now
        return listOf(
            DetectorEvent(
                tMs = now,
                type = DetectorEventType.STATION_ARRIVED,
                dwellS = null,
                intervalS = intervalS,
                note = note,
            ),
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
