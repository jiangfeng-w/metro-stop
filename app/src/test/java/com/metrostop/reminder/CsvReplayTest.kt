package com.metrostop.reminder

import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.Direction
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.model.Line
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.replay.CsvReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * CSV 回放回归用例（M1 验收项 3、硬性规则 2 的「留下一个 replay 回归用例」）。
 *
 * 用**合成行程**生成 sensor CSV —— 真实加速度信号，真的走一遍 FeatureExtractor + 状态机，
 * 断言事件序列 / 计数 / 自动结束；并验证「同一份 CSV 两次回放结果完全一致」。
 *
 * 信号设计（正交，贴近物理）：
 * - 车厢振动加在**沿重力方向**（手机平放时 = z），只推高 `vib`，不影响 H；
 * - 纵向制动加在**水平方向**（x），只推高 `H`，不推高 vib（3–20 Hz 带通滤掉直流）。
 *
 * 真机实测后：把导出的 `sensor_*.csv` 放进 `app/src/test/resources/replay/`，
 * 再补一条「实测 CSV 回归」用例（阈值改动后必须重跑）。
 *
 * ⚠️ 2026-09-28：合成信号沿用「手摇 = 列车」的旧量级（vib≈1.5，超出真实乘车证据带
 * [0.10, 0.35]）——真实通勤实测已证伪「手摇语义等价列车运行」，本类只锁**状态机语义**
 * （计数 / 提醒 / 间隔门槛 / 自动结束），故注入关闭乘车证据门；
 * 真实振动量级下的行为由 [RealCommuteRegressionTest] 用真实通勤 CSV 锁定。
 */
class CsvReplayTest {

    private val config = TuningConfig(evidenceGateEnabled = false)
    private val fs = 50.0

    private enum class Phase { STATIC, CRUISE, BRAKE, STILL }

    private data class Seg(val fromSec: Double, val toSec: Double, val phase: Phase)

    /**
     * 一次完整行程（k = 2，2026-09-28 起改为「站台开始」场景）：
     * 预热（静立）→ 上车站停稳（忽略）→ 起步巡航 → 停车（n=1，发 D−1 提醒）
     * → 起步巡航 → 停车（n=2=k，发到达提醒并排定 30 s 后结束）→ 静置等自动结束。
     *
     * ⚠️ v2 起 hasRun 可由巡航振动直置（vib>阈值持续 30 s）——若在首停前安排长巡航，
     * 首停会被计为真实第 1 站（信号语义本来就是「车在动」）；要表达「上车站本身」，
     * 首停必须紧跟预热（真实的站台开始姿势）。
     */
    private val timeline = listOf(
        Seg(0.0, 21.0, Phase.STATIC),    // 预热（startGraceSec = 20）
        Seg(21.0, 38.0, Phase.STILL),    // → FIRST_STOP_IGNORED（上车站本身，到站 ~29）
        Seg(38.0, 146.0, Phase.CRUISE),  // → DEPART（起步确认 10 s）
        Seg(146.0, 151.0, Phase.BRAKE),
        Seg(151.0, 171.0, Phase.STILL),  // → n=1 = k-1 → ALERT_PREV（到站 ~159）
        Seg(171.0, 286.0, Phase.CRUISE), // → DEPART
        Seg(286.0, 291.0, Phase.BRAKE),
        Seg(291.0, 336.0, Phase.STILL),  // → n=2 = k → ALERT_ARRIVED，30 s 后 MONITOR_END
    )

    private fun phaseAt(t: Double): Phase {
        for (s in timeline) if (t >= s.fromSec && t < s.toSec) return s.phase
        return timeline.last().phase
    }

    /** 生成 sensor CSV（表头与总纲 7.2 一致；vib/h/state/n 列写占位，回放自行重算） */
    private fun buildCsv(): String {
        val total = (timeline.last().toSec * fs).toInt()
        val sb = StringBuilder(total * 72)
        sb.append("t_ms,utc_ms,ax,ay,az,lx,ly,lz,gx,gy,gz,vib,h,state,n\n")
        for (i in 0 until total) {
            val t = i / fs
            val tMs = (t * 1000).toLong()
            val vibSignal = when (phaseAt(t)) {
                Phase.CRUISE, Phase.BRAKE -> 1.5 * sin(2 * PI * 10.0 * t)
                Phase.STATIC, Phase.STILL -> 0.0
            }
            val brakeSignal = when (phaseAt(t)) {
                Phase.BRAKE -> 1.0
                else -> 0.0
            }
            sb.append(tMs).append(',')
            sb.append(1_700_000_000_000L + tMs).append(',')
            sb.append("0.0000,0.0000,9.8000,") // 手机平放：重力沿 +z
            sb.append(String.format(Locale.US, "%.4f,%.4f,%.4f,", brakeSignal, 0.0, vibSignal))
            sb.append(",,,,0.0,0.0,IDLE,0\n")
        }
        return sb.toString()
    }

    private fun route(k: Int): RouteSpec {
        val stations = (0..k).map { Station(id = "s$it", name = "站$it") }
        val dir = Direction("up", "上行", stations)
        val line = Line("l", "测试线", listOf(dir))
        return RouteSpec.of(line, dir, "s0", "s$k")!!
    }

    @Test
    fun `合成行程回放_计数与提醒序列符合预期`() {
        val result = CsvReplay.replay(buildCsv(), route(2), config)
        val types = result.events.map { it.type }

        println("回放事件序列：")
        result.events.forEach { println("  ${it.tMs} ${it.type} ${it.note ?: ""}") }

        assertEquals("识别站数应等于 k=2", 2, result.finalStationCount)
        assertEquals("应有 2 次到站计数", 2, types.count { it == DetectorEventType.STATION_ARRIVED })
        assertEquals("应有 2 次起步", 2, types.count { it == DetectorEventType.DEPART })
        assertTrue("首站应被忽略（上车站本身）", types.contains(DetectorEventType.FIRST_STOP_IGNORED))
        assertTrue("n = k-1 应发 D−1 提醒", types.contains(DetectorEventType.ALERT_PREV))
        assertTrue("n = k 应发到达提醒", types.contains(DetectorEventType.ALERT_ARRIVED))
        assertTrue("应自动结束", types.contains(DetectorEventType.MONITOR_END))
        assertEquals(
            "自动结束原因应为已到达",
            EndReason.ARRIVED,
            result.events.last { it.type == DetectorEventType.MONITOR_END }.note,
        )
    }

    @Test
    fun `同一份CSV两次回放结果完全一致`() {
        val csv = buildCsv()
        val a = CsvReplay.replay(csv, route(2), config)
        val b = CsvReplay.replay(csv, route(2), config)
        assertEquals(a.samples, b.samples)
        assertEquals(a.finalStationCount, b.finalStationCount)
        assertEquals("因果实现 + 相同参数 → 事件序列必须逐行一致", a.comparableLines(), b.comparableLines())
    }

    @Test
    fun `解析跳过表头与坏行`() {
        val csv = """
            t_ms,utc_ms,ax,ay,az,lx,ly,lz,gx,gy,gz,vib,h,state,n
            1000,1700000001000,0.1,0.2,9.8,0.0,0.0,0.0,,,,0.1,0.1,CRUISE,0
            bad,line
            2000,1700000002000,0.2,0.2,9.8,0.0,0.0,0.0,,,,0.2,0.2,CRUISE,0
        """.trimIndent()
        val rows = CsvReplay.parse(csv)
        assertEquals(2, rows.size)
        assertEquals(1000L, rows[0].tMs)
        assertEquals(2000L, rows[1].tMs)
    }

    @Test
    fun `数据断流在回放中被记录`() {
        // 构造：21 s 巡航 → 断开 10 s → 继续 30 s 巡航（无停站）
        val sb = StringBuilder()
        sb.append("t_ms,utc_ms,ax,ay,az,lx,ly,lz,gx,gy,gz,vib,h,state,n\n")
        var t = 0.0
        while (t < 21.0) {
            appendRow(sb, t, vib = 1.5 * sin(2 * PI * 10.0 * t), brake = 0.0)
            t += 1.0 / fs
        }
        t += 10.0 // 断流
        val resume = t + 40.0
        while (t < resume) {
            appendRow(sb, t, vib = 1.5 * sin(2 * PI * 10.0 * t), brake = 0.0)
            t += 1.0 / fs
        }
        val result = CsvReplay.replay(sb.toString(), route(1), config)
        val types = result.events.map { it.type }
        assertTrue("应记录 DATA_GAP", types.contains(DetectorEventType.DATA_GAP))
        assertTrue("应记录 DATA_RESUME", types.contains(DetectorEventType.DATA_RESUME))
    }

    private fun appendRow(sb: StringBuilder, t: Double, vib: Double, brake: Double) {
        val tMs = (t * 1000).toLong()
        sb.append(tMs).append(',')
        sb.append(1_700_000_000_000L + tMs).append(',')
        sb.append("0.0000,0.0000,9.8000,")
        sb.append(String.format(Locale.US, "%.4f,%.4f,%.4f,", brake, 0.0, vib))
        sb.append(",,,,0.0,0.0,IDLE,0\n")
    }
}
