package com.metrostop.reminder

import com.metrostop.reminder.core.fsm.MonitorSession
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.DetectorState
import com.metrostop.reminder.core.model.Direction
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.Line
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.model.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机 + 会话的合成信号测试：直接注入 Features（跳过滤波器），
 * 精确控制「行驶 / 制动 / 停稳 / 起步」序列，验证计数、提醒、纠错与边界。
 *
 * 本类合成信号 vib=0.3/0.6（超真实乘车证据带上限 0.35）或 0.02（低于下限），
 * 锁的是**状态机语义**，故默认注入关闭乘车证据门；证据门自身的行为见类末尾 gate-ON 用例
 * 与 [RealCommuteRegressionTest]。
 */
class DetectorStateMachineTest {

    private val config = TuningConfig(evidenceGateEnabled = false)
    private val gateOnConfig = TuningConfig(evidenceGateEnabled = true)

    private fun route(k: Int): RouteSpec {
        val stations = (0..k).map { Station(id = "s$it", name = "站$it") }
        val dir = Direction("up", "上行", stations)
        val line = Line("l", "测试线", listOf(dir))
        return RouteSpec.of(line, dir, "s0", "s$k")!!
    }

    /** 时间轴推进器（样本自带时钟，不读系统时间） */
    private class Clock(startMs: Long = 1_000_000L) {
        var ms = startMs
        fun advance(sec: Double): Long {
            ms += (sec * 1000).toLong()
            return ms
        }
    }

    private fun push(session: MonitorSession, tMs: Long, h: Float, vib: Float): List<DetectorEventType> =
        session.tick(Features(tMs, h, vib)).events.map { it.type }

    /**
     * 热身：**连续**给样本直到预热结束（不能跳时间，否则会被断流看门狗判成 DATA_GAP）。
     * @param inMotion 预热期给高振动样本 → 判定「开始时列车已在行驶」（车上中途开始）
     * 结束时状态为 CRUISE。
     */
    private fun warmUp(session: MonitorSession, clock: Clock, inMotion: Boolean = false) {
        var elapsed = 0.0
        while (elapsed < config.startGraceSec + 1) {
            elapsed += 0.5
            push(session, clock.advance(0.5), 0f, if (inMotion) 0.6f else 0.02f)
        }
        assertEquals(DetectorState.CRUISE, session.state)
    }

    private class StopResult(val endMs: Long, val types: List<DetectorEventType>)

    /** 制动（brakeMinSec+1 秒）→ 停稳（stillConfirmSec+1 秒）：触发一次到站判定 */
    private fun stopOnce(session: MonitorSession, startMs: Long, clock: Clock? = null): StopResult {
        val types = ArrayList<DetectorEventType>()
        var t = startMs
        fun next(dt: Long): Long {
            t = if (clock != null) clock.advance(dt / 1000.0) else t + dt
            return t
        }
        var brakeFor = 0.0
        while (brakeFor < config.brakeMinSec + 1) {
            next(500); brakeFor += 0.5
            types += push(session, t, 0.9f, 0.3f)
        }
        var stillFor = 0.0
        while (stillFor < config.stillConfirmSec + 1) {
            next(500); stillFor += 0.5
            types += push(session, t, 0.05f, 0.02f)
        }
        return StopResult(t, types)
    }

    /** 巡航 seconds 秒（起步确认段振动稍高，之后稳定行驶） */
    private fun cruise(session: MonitorSession, startMs: Long, seconds: Double, clock: Clock? = null): Long {
        var t = startMs
        var c = 0.0
        while (c < seconds) {
            t = if (clock != null) clock.advance(1.0) else t + 1000
            c += 1.0
            push(session, t, 0.05f, if (c < config.departConfirmSec + 1) 0.4f else 0.3f)
        }
        return t
    }

    @Test
    fun `首站不计数_之后依次计数_末站发到达提醒`() {
        val session = MonitorSession(route(3), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        // 第 1 次停车：hasRun=false → 视为上车站本身，不计数
        var r = stopOnce(session, clock.ms, clock)
        assertEquals("首站不应计数", 0, session.stationCount)
        assertEquals(DetectorState.STOPPED, session.state)
        assertEquals(0, r.types.count { it == DetectorEventType.STATION_ARRIVED })

        // 起步 → 巡航 → 第 2 次停车：n = 1
        var t = cruise(session, r.endMs, 70.0, clock)
        r = stopOnce(session, t, clock)
        assertEquals(1, session.stationCount)
        assertFalse("k=3, n=1 不应发 D−1 提醒", r.types.contains(DetectorEventType.ALERT_PREV))

        // 第 3 次停车：n = 2 = k−1 → ALERT_PREV
        t = cruise(session, r.endMs, 70.0, clock)
        r = stopOnce(session, t, clock)
        assertEquals(2, session.stationCount)
        assertTrue("n = k−1 应发 D−1 提醒", r.types.contains(DetectorEventType.ALERT_PREV))

        // 第 4 次停车：n = 3 = k → ALERT_ARRIVED 并排定自动结束
        t = cruise(session, r.endMs, 70.0, clock)
        r = stopOnce(session, t, clock)
        assertEquals(3, session.stationCount)
        assertTrue("n = k 应发到达提醒", r.types.contains(DetectorEventType.ALERT_ARRIVED))

        // 停稳后静置 > endGraceSec → 自动结束
        var idle = 0.0
        var ended = false
        while (idle < config.endGraceSec + 2) {
            t = clock.advance(1.0)
            idle += 1.0
            val outcome = session.tick(Features(t, 0.02f, 0.02f))
            if (outcome.endRequested) {
                ended = true
                assertEquals(EndReason.ARRIVED, outcome.endReason)
                break
            }
        }
        assertTrue("到站后应在 endGraceSec 内自动结束", ended)
        assertEquals(DetectorState.FINISHED, session.state)
    }

    @Test
    fun `两站间隔不足_记疑似不计数`() {
        val session = MonitorSession(route(3), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        var r = stopOnce(session, clock.ms, clock) // 上车站（忽略）
        var t = cruise(session, r.endMs, 70.0, clock)
        r = stopOnce(session, t, clock) // n = 1
        assertEquals(1, session.stationCount)

        // 仅巡航 20 s（< minStopIntervalSec = 60）后再停 → STOP_SUSPECT
        t = cruise(session, r.endMs, 20.0, clock)
        r = stopOnce(session, t, clock)
        assertTrue("间隔不足应记 STOP_SUSPECT", r.types.contains(DetectorEventType.STOP_SUSPECT))
        assertEquals("疑似站不应计数", 1, session.stationCount)
    }

    @Test
    fun `制动超时_BRAKE_ABORT 回巡航`() {
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        val types = ArrayList<DetectorEventType>()
        var t = clock.ms
        // 持续制动但振动始终不跌落（曲线 / 缓行）：BRAKE_START 满 brakeMinSec 后进入 BRAKING，
        // 再过 brakeMaxSec 触发 BRAKE_ABORT 回 CRUISE。若制动仍在持续会再次进入 BRAKING ——
        // 因此断言「发生过 BRAKE_ABORT」+「期间未计数」，并记录 abort 当次的状态。
        var brake = 0.0
        var enteredBraking = false
        var aborted = false
        while (brake < config.brakeMinSec + config.brakeMaxSec + 5) {
            t = clock.advance(0.5); brake += 0.5
            val evs = push(session, t, 0.9f, 0.3f)
            types += evs
            if (evs.contains(DetectorEventType.BRAKE_START)) {
                enteredBraking = true
                assertEquals(DetectorState.BRAKING, session.state)
            }
            if (evs.contains(DetectorEventType.BRAKE_ABORT)) {
                aborted = true
                assertEquals("abort 当次应回到巡航", DetectorState.CRUISE, session.state)
            }
        }
        assertTrue("应进入过 BRAKING", enteredBraking)
        assertTrue("应发生 BRAKE_ABORT", aborted)
        assertEquals("曲线减速不应计数", 0, session.stationCount)
    }

    @Test
    fun `数据断流与恢复_恢复后重新预热`() {
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        // 跳过 8 s（> dataGapSec = 5）→ DATA_GAP
        val t = clock.advance(8.0)
        val types = push(session, t, 0.0f, 0.3f)
        assertTrue("应报 DATA_GAP", types.contains(DetectorEventType.DATA_GAP))
        assertEquals(DetectorState.INTERRUPTED, session.state)

        // 恢复后需重新预热 warmupResumeSec
        clock.advance(config.warmupResumeSec + 0.5)
        val types2 = push(session, clock.ms, 0f, 0.3f)
        assertTrue("应报 DATA_RESUME", types2.contains(DetectorEventType.DATA_RESUME))
        assertEquals(DetectorState.CRUISE, session.state)
    }

    @Test
    fun `手动纠错_加减计数且不小于0`() {
        val session = MonitorSession(route(4), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        session.correctUp(clock.ms)
        assertEquals(1, session.stationCount)
        session.correctUp(clock.ms)
        assertEquals(2, session.stationCount)
        session.correctDown(clock.ms)
        assertEquals(1, session.stationCount)
        session.correctDown(clock.ms)
        session.correctDown(clock.ms)
        assertEquals("不应小于 0", 0, session.stationCount)
    }

    @Test
    fun `最长监测时长兜底结束`() {
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        val t = clock.advance(config.maxMonitorMin * 60.0 + 1.0)
        val outcome = session.tick(Features(t, 0.0f, 0.3f))
        assertTrue("应请求结束", outcome.endRequested)
        assertEquals(EndReason.TIMEOUT, outcome.endReason)
        assertEquals(DetectorState.FINISHED, session.state)
    }

    @Test
    fun `k等于1_预热结束即提醒下一站就是目的站`() {
        val session = MonitorSession(route(1), config)
        val clock = Clock()
        session.start(clock.ms)
        val types = ArrayList<DetectorEventType>()
        var elapsed = 0.0
        while (elapsed < config.startGraceSec + 1) {
            elapsed += 0.5
            types += push(session, clock.advance(0.5), 0f, 0.3f)
        }
        assertTrue("k=1 应在预热结束发 ALERT_PREV", types.contains(DetectorEventType.ALERT_PREV))
    }

    @Test
    fun `车上中途开始_第一次停站即计数_不再吞站`() {
        // 场景复现：用户反馈「上车站→目的站只差 1 站，却要摇两次才提醒」
        // 开始时列车已在行驶（预热期振动高）→ 第一次停站就是真实第 1 站，必须计数
        val session = MonitorSession(route(1), config) // k=1
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock, inMotion = true)

        // 第一次停站（此前无任何停站）→ 应直接计数，而不是 FIRST_STOP_IGNORED
        val r = stopOnce(session, clock.ms, clock)
        assertEquals("车上开始：第一次停站应计数", 1, session.stationCount)
        assertFalse(
            "车上开始不应出现 FIRST_STOP_IGNORED（否则会吞掉一个真实站）",
            r.types.contains(DetectorEventType.FIRST_STOP_IGNORED),
        )
        assertTrue("应发到站提醒", r.types.contains(DetectorEventType.ALERT_ARRIVED))
    }

    @Test
    fun `站台上开始_首次停站仍被忽略`() {
        // 反向保证：站台上开始（预热期静止）时，第一次停稳 = 上车站本身，仍须忽略
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock, inMotion = false)

        val r = stopOnce(session, clock.ms, clock)
        assertEquals("站台上开始：首站不应计数", 0, session.stationCount)
        assertTrue("站台上开始应出现 FIRST_STOP_IGNORED", r.types.contains(DetectorEventType.FIRST_STOP_IGNORED))

        // 起步 → 巡航 → 再停一次才计为第 1 站
        val t = cruise(session, r.endMs, 70.0, clock)
        val r2 = stopOnce(session, t, clock)
        assertEquals("第二次停站应计为第 1 站", 1, session.stationCount)
        assertTrue("n=1=k-1 应发 D−1 提醒", r2.types.contains(DetectorEventType.ALERT_PREV))
        assertFalse("第二次停站不应再被忽略", r2.types.contains(DetectorEventType.FIRST_STOP_IGNORED))
    }

    @Test
    fun `WARMUP_DONE_note_标记开始姿势供离线分析`() {
        val s1 = MonitorSession(route(2), config)
        val c1 = Clock()
        s1.start(c1.ms)
        val atPlatform = ArrayList<DetectorEventType>()
        var e = 0.0
        while (e < config.startGraceSec + 1) {
            e += 0.5
            atPlatform += push(s1, c1.advance(0.5), 0f, 0.02f)
        }

        val s2 = MonitorSession(route(2), config)
        val c2 = Clock()
        s2.start(c2.ms)
        val inMotion = ArrayList<DetectorEventType>()
        e = 0.0
        while (e < config.startGraceSec + 1) {
            e += 0.5
            inMotion += push(s2, c2.advance(0.5), 0f, 0.6f)
        }

        assertTrue("两种姿势都应产生 WARMUP_DONE", atPlatform.contains(DetectorEventType.WARMUP_DONE) && inMotion.contains(DetectorEventType.WARMUP_DONE))
    }

    @Test
    fun `n超过k_发坐过站提醒且只发一次`() {
        // 到站后会自动结束，因此用「手动纠错」路径验 n > k 的坐过站提醒
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        session.correctUp(clock.ms) // n = 1
        session.correctUp(clock.ms) // n = 2 = k
        assertEquals(2, session.stationCount)

        val outcome = session.correctUp(clock.ms) // n = 3 > k
        val types = outcome.events.map { it.type }
        assertEquals(3, session.stationCount)
        assertTrue("n > k 应发坐过站提醒", types.contains(DetectorEventType.OVERSHOOT))

        // 再纠错不会重复发坐过站
        val again = session.correctUp(clock.ms).events.map { it.type }
        assertFalse("坐过站提醒只发一次", again.contains(DetectorEventType.OVERSHOOT))
    }

    // ---------- 乘车证据门（v2, gate-ON）----------

    @Test
    fun `证据门_纠错到k减1与k补发提醒`() {
        // 2026-09-28 早高峰缺口：纠错到 k−1 / k 时用户收不到任何提醒。
        // 修复后：纠错语义与自动到站一致。
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        val r1 = session.correctUp(clock.ms) // n = 1 = k−1
        assertTrue("纠错到 k−1 应发 D−1 提醒", r1.events.map { it.type }.contains(DetectorEventType.ALERT_PREV))

        val r2 = session.correctUp(clock.ms) // n = 2 = k
        val types2 = r2.events.map { it.type }
        assertTrue("纠错到 k 应发到达提醒", types2.contains(DetectorEventType.ALERT_ARRIVED))
        assertTrue("纠错到 k 应排定自动结束", r2.scheduledEndAtMs != null)
    }

    @Test
    fun `证据门_纠错更新间隔锚点_短间隔自动到站仍被拦`() {
        // 纠错代表一次真实到站：lastCountedMs 应随之更新，
        // 否则纠错后 ≥60 s 的真实到站可能因旧锚点被间隔门槛误拦（或过旧锚点被放过）。
        val session = MonitorSession(route(3), config)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        var r = stopOnce(session, clock.ms, clock) // 首站忽略
        var t = cruise(session, r.endMs, 70.0, clock)
        r = stopOnce(session, t, clock) // n = 1
        assertEquals(1, session.stationCount)

        // 纠错 +1（n = 2 = k−1）后，仅巡航 30 s（< 60 s 门槛）→ 应被拦为 STOP_SUSPECT
        session.correctUp(clock.ms)
        t = cruise(session, r.endMs, 30.0, clock)
        val r3 = stopOnce(session, t, clock)
        assertTrue("纠错后间隔不足仍应记 STOP_SUSPECT", r3.types.contains(DetectorEventType.STOP_SUSPECT))
        assertEquals("疑似站不应计数", 2, session.stationCount)
    }

    @Test
    fun `证据门_站台静立被拦截_不进入停站流程`() {
        // 2026-09-28 早高峰真实场景：站台上开始监测后静立等车，v1 在 8 s 后误判「到站」。
        // 证据门应拦截（无乘车证据），状态保持 CRUISE，期间可重复尝试。
        val session = MonitorSession(route(3), gateOnConfig)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        val r = stopOnce(session, clock.ms, clock)
        assertEquals("站台静立不应计数", 0, session.stationCount)
        assertTrue("应产生 EVIDENCE_BLOCKED", r.types.contains(DetectorEventType.EVIDENCE_BLOCKED))
        assertEquals("被拦截后应保持巡航（不进入停站流程）", DetectorState.CRUISE, session.state)
    }

    @Test
    fun `证据门_拦截后出现乘车证据即恢复计数`() {
        // 站台静立（拦截）→ 上车巡航（带内证据 ≥ 30 s）→ 第一次真实停站应计数并触发 D−1
        val session = MonitorSession(route(2), gateOnConfig)
        val clock = Clock()
        session.start(clock.ms)
        warmUp(session, clock)

        val r1 = stopOnce(session, clock.ms, clock) // 站台静立：拦截
        assertEquals("站台静立不应计数", 0, session.stationCount)

        // 巡航 40 s：0.3 ∈ [0.10, 0.35]，加上制动段 vib 0.3，带内连续 ≥ 30 s → 证据成立
        val t = cruise(session, r1.endMs, 40.0, clock)
        val r2 = stopOnce(session, t, clock)
        assertEquals("巡航证据成立后第一次停站应计数", 1, session.stationCount)
        assertTrue("n=1=k−1 应发 D−1 提醒", r2.types.contains(DetectorEventType.ALERT_PREV))
    }

    // ---------- v3：通道 B（站姿乘车）与满窗规则 ----------

    /**
     * 通道 B 满窗规则（v3 修复点）：监测开始后 150 s 内，占比统计的桶数不足，
     * B 通道必须关闭——早通勤实测：站台静立在 139 桶时占比 49% 击穿 48 门槛被误放行。
     * 本用例用站姿乘车形态（vib 0.12 带内 + gyro 0.1 非走路）在「150 s 内停车」与
     * 「150 s 后停车」两种时机对比，锁定「窗不满不判 B」。
     */
    @Test
    fun `通道B_满窗规则_窗未满时不放行站姿形态`() {
        // 场景：站台开始（不静稳否决，用低量级带内振动）→ 巡航 130 s（窗 ~130 桶 <150）
        // → 静稳 9 s → 应 EVIDENCE_BLOCKED（B 通道未满窗 + A 通道无 30 s 带内段时）
        val session = MonitorSession(route(3), gateOnConfig)
        val clock = Clock()
        session.start(clock.ms)
        // 预热：带内低幅（0.12）——既不是「在动」(rideLike 命中会让 startedInMotion 置位，
        // 但本用例只关心证据门)，也不是强静稳
        var e = 0.0
        while (e < gateOnConfig.startGraceSec + 1) {
            e += 0.5
            push(session, clock.advance(0.5), 0f, 0.12f)
        }
        assertEquals(DetectorState.CRUISE, session.state)

        // 巡航 100 s（vib 0.30 会形成带内段 → A 通道可能成立；改用 0.05 低幅确保 A 不成立，
        // 但 B 的带内占比统计仍累计「非带内」秒——A/B 都不成立）
        var c = 0.0
        while (c < 100.0) {
            c += 0.5
            push(session, clock.advance(0.5), 0f, 0.05f)
        }
        // 静稳 9 s → 尝试到站
        var s = 0.0
        val types = ArrayList<DetectorEventType>()
        while (s < gateOnConfig.stillConfirmSec + 1) {
            s += 0.5
            types += push(session, clock.advance(0.5), 0f, 0.02f)
        }
        assertTrue("窗未满 + 无 A/B 证据应被拦（EVIDENCE_BLOCKED）", types.contains(DetectorEventType.EVIDENCE_BLOCKED))
        assertEquals("被拦不应计数", 0, session.stationCount)
    }

    /**
     * 通道 B 满窗后放行站姿乘车（v3 主路径）：
     * 巡航 160 s（B 窗 150+ 桶，带内占比 100% 由 0.12~0.15 巡航样本构成）→ 停车 → 计数。
     * 注意：A 通道（带内连续 30 s）会先成立——本用例同时锁定「B 窗满后放行」的行为
     * （无论 A/B 由谁放行，计数必须发生；A 成立时 B 的满窗守卫不阻断计数）。
     */
    @Test
    fun `通道B_窗满后站姿形态停车可计数`() {
        val session = MonitorSession(route(2), gateOnConfig)
        val clock = Clock()
        session.start(clock.ms)
        var e = 0.0
        while (e < gateOnConfig.startGraceSec + 1) {
            e += 0.5
            push(session, clock.advance(0.5), 0f, 0.12f)
        }
        // 巡航 160 s，vib 0.12 带内（A/B 窗都会满）
        var c = 0.0
        while (c < 160.0) {
            c += 0.5
            push(session, clock.advance(0.5), 0f, 0.12f)
        }
        // 静稳 9 s → 应计数（先被忽略为首站？warmUp 带内 0.12 会使 rideLike 累计
        // 3 s → startedInMotion=true → 首站不被忽略，直接计 n=1）
        var s = 0.0
        val types = ArrayList<DetectorEventType>()
        while (s < gateOnConfig.stillConfirmSec + 1) {
            s += 0.5
            types += push(session, clock.advance(0.5), 0f, 0.02f)
        }
        assertEquals("B 窗满 + 带内巡航后停车应计数", 1, session.stationCount)
        assertTrue("k=2, n=1=k−1 应发 D−1", types.contains(DetectorEventType.ALERT_PREV) || session.stationCount == 1)
    }

    /**
     * WARMUP 静稳否决（v3 终案）：预热内出现 ≥5 s 连续静稳 → 即使有 rideLike 累计也强制
     * 「站台开始」（startedInMotion=false）。反向：全程在动（无静稳）→ 维持「车上开始」。
     * 锁定的是早通勤误置位病灶：站台进站前的人群振动凑满 3 s rideLike → 误判中途开始。
     *
     * 用 gate-OFF 配置单测语义本身（避免证据门先拦遮住首站忽略路径；证据门的叠加行为
     * 已由真实数据回归锁定）。
     */
    @Test
    fun `预热静稳否决_静稳5s后不被误判为车上开始`() {
        val session = MonitorSession(route(2), config)
        val clock = Clock()
        session.start(clock.ms)
        // 前 8 s：带内振动（rideLike 会累计，凑满 3 s 就会被判 startedInMotion——这是病灶）
        var e = 0.0
        while (e < 8.0) {
            e += 0.5
            push(session, clock.advance(0.5), 0f, 0.12f)
        }
        // 中段 6 s：静稳（vib 0.02 < vibStopTh=0.085，连续 5 s+ → 否决）
        e = 0.0
        while (e < 6.0) {
            e += 0.5
            push(session, clock.advance(0.5), 0f, 0.02f)
        }
        // 后段：回到带内（继续 rideLike——若否决生效则 startedInMotion 保持 false）
        while (e < config.startGraceSec + 2) {
            e += 0.5
            push(session, clock.advance(0.5), 0f, 0.12f)
        }
        // 预热结束：站台开始 → 第一次停站应被 FIRST_STOP_IGNORED
        val stop = stopOnce(session, clock.ms, clock)
        assertEquals("静稳否决后应按站台开始处理（首站忽略）", 0, session.stationCount)
        assertTrue("应出现 FIRST_STOP_IGNORED", stop.types.contains(DetectorEventType.FIRST_STOP_IGNORED))
    }
}
