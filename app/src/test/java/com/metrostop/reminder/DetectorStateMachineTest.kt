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
 */
class DetectorStateMachineTest {

    private val config = TuningConfig.Default

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
}
