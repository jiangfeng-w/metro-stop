package com.metrostop.reminder

import com.metrostop.reminder.core.cell.CellZoneLine
import com.metrostop.reminder.core.cell.CellZoneStation
import com.metrostop.reminder.core.fsm.MonitorSession
import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.route.LineRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两站最短间隔门槛豁免回归（2026-10-08 融合层 bug 第二例）。
 *
 * 背景：`minStopIntervalSec=60` 为拦信号停车（区间内急停再起步）而设，但 10-08 早 4 号线暴露
 * **误伤真实到站**——市二医院到站计数后仅 46 s（市二医院停站晚计 70 s + 太升南路进近快），
 * 太升南路到站候选被间隔门槛拦成 `STOP_SUSPECT`，**到站提醒丢失**（现场表现：已到站却无提醒）。
 *
 * 修复（`MonitorSession.translateArrival`）：**单站站区精确认**（确认区间恰好等于期望站位本身，
 * 即区间 = [p..p]）＝位置证据强到「列车就在这一站」，此时间隔门槛豁免。共享区间（区间覆盖相邻
 * 多站，如实测 4 号线 701 区覆盖市二医院+太升南路）**不豁免**——区间内无法区分「同一站重复报」
 * 与「相邻站到站」，必须保留门槛防区间内重复计数。
 *
 * 合成时间线（cd4 玉双路→太升南路 2 站；`100:100`/`200:200` 注入站区）：
 * 巡航 104 s → 市二医院进区 → 停站1 静稳（计数 1）→ 巡航 31 s → 太升南路进区
 * → 停站2 静稳（候选距计数 1 约 42 s < 60 s 门槛）。
 */
class MonitorSessionIntervalTest {

    private val repo: LineRepository by lazy {
        LineRepository.parse(File("src/main/assets/subway_lines.json").readText())
            .getOrElse { throw AssertionError("线路资产解析失败：$it") }
    }

    /** 玉双路 → 太升南路（4 号线万盛方向，2 站） */
    private fun cd4Route(): RouteSpec = RouteSpec.of(
        repo.line("cd4") ?: throw AssertionError("找不到线路 cd4"),
        repo.direction("cd4", "cd4_to_wansheng") ?: throw AssertionError("找不到方向"),
        "cd4_s22",
        "cd4_s20",
    ) ?: throw AssertionError("路线构造失败")

    /** 单站站区：市二医院=100:100、太升南路=200:200 */
    private fun zoneSingle() = CellZoneLine(
        lineId = "cd4",
        directionId = "cd4_to_wansheng",
        stations = listOf(
            CellZoneStation("cd4_s21", listOf("100:100")),
            CellZoneStation("cd4_s20", listOf("200:200")),
        ),
    )

    /** 共享站区变体：两站共用 100:100（对应实测 701 区覆盖相邻两站） */
    private fun zoneShared() = CellZoneLine(
        lineId = "cd4",
        directionId = "cd4_to_wansheng",
        stations = listOf(
            CellZoneStation("cd4_s21", listOf("100:100")),
            CellZoneStation("cd4_s20", listOf("100:100")),
        ),
    )

    private class Run(val session: MonitorSession, val events: List<DetectorEvent>, val t0: Long)

    private fun drive(sharedZone: Boolean): Run {
        val t0 = 100_000L
        val session = MonitorSession(
            cd4Route(),
            TuningConfig.Default,
            if (sharedZone) zoneShared() else zoneSingle(),
        )
        val events = ArrayList<DetectorEvent>()
        events += session.start(t0).events

        fun feed(fromMs: Long, toMs: Long, vib: Float) {
            var t = fromMs
            while (t <= toMs) {
                events += session.tick(Features(t, h = 0.01f, vib = vib)).events
                t += 1000L
            }
        }

        // 目的站小区：单站区 = 200:200（进目的站区）；共享区 = 100:100（同一小区恒驻留，不产生区转移）
        val destCell = if (sharedZone) "100:100" else "200:200"

        feed(t0 + 1_000L, t0 + 104_000L, vib = 0.3f) // 起步 + 巡航（乘车带证据 ≥150s 窗 48%）
        repeat(3) { i ->
            events += session.onCellSample(t0 + 105_000L + 1000L * i, "100:100", emptyList())
        }
        feed(t0 + 108_000L, t0 + 118_000L, vib = 0.005f) // 停站 1 → 计入市二医院
        feed(t0 + 119_000L, t0 + 149_000L, vib = 0.3f) // 离站巡航 31 s（实测太升南路段较短）
        repeat(3) { i ->
            events += session.onCellSample(t0 + 125_000L + 1000L * i, destCell, emptyList())
        }
        feed(t0 + 150_000L, t0 + 195_000L, vib = 0.005f) // 停站 2（距计数 1 约 44 s）
        return Run(session, events, t0)
    }

    @Test
    fun `单站站区精确认_间隔小于门槛_到站仍计数且发提醒`() {
        val run = drive(sharedZone = false)
        val arrivals = run.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

        assertEquals(
            "事件流: ${run.events.joinToString { "${it.type.name}@${it.tMs - run.t0}:${it.note}" }}",
            listOf("市二医院", "太升南路"),
            arrivals.map { it.stationName },
        )
        assertEquals(2, run.session.stationCount)

        val gapSec = (arrivals[1].tMs - arrivals[0].tMs) / 1000.0
        assertTrue(
            "本用例前提：两站计数间隔 < minStopIntervalSec（实测 46 s）: $gapSec",
            gapSec < TuningConfig.Default.minStopIntervalSec,
        )
        assertTrue(
            "不得被间隔门槛误伤: ${run.events.filter { it.type == DetectorEventType.STOP_SUSPECT }}",
            run.events.none { it.type == DetectorEventType.STOP_SUSPECT },
        )
        assertTrue(
            "三级提醒齐全: ${run.events.map { it.type }}",
            run.events.any { it.type == DetectorEventType.ALERT_PREV } &&
                run.events.any { it.type == DetectorEventType.ALERT_DEST_SOON } &&
                run.events.any { it.type == DetectorEventType.ALERT_ARRIVED },
        )
        assertTrue(
            "数满自动结束",
            run.events.any { it.type == DetectorEventType.MONITOR_END },
        )
    }

    @Test
    fun `共享站区_间隔门槛保留_区间内重复候选仍被拦`() {
        val run = drive(sharedZone = true)
        val trace = run.events.joinToString { "${it.type.name}@${it.tMs - run.t0}:${it.note}" }

        // 计数 1 = 市二医院（共享区间 1..2 的首站，首计数不受门槛约束）
        val arrivals = run.events.filter { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals("共享区间下不得重复计数: $trace", 1, run.session.stationCount)
        assertEquals(listOf("市二医院"), arrivals.map { it.stationName })

        // 计数后的重复候选（+2 s 的同站重报、+44 s 的下一站候选）都在区间内 → 间隔门槛拦下
        val suspects = run.events.filter { it.type == DetectorEventType.STOP_SUSPECT }
        assertTrue("共享区间的重复/续接候选应由间隔门槛拦下: $trace", suspects.isNotEmpty())
        assertTrue(
            "停站 2 静稳窗内的候选也必须被拦（t+150s..t+195s）: ${suspects.map { it.tMs - run.t0 }} / $trace",
            suspects.any { it.tMs in (run.t0 + 150_000L)..(run.t0 + 195_000L) },
        )
        assertTrue(
            "未计满不得发「已到站」",
            run.events.none { it.type == DetectorEventType.ALERT_ARRIVED },
        )
    }
}
