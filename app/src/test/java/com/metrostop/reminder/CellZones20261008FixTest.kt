package com.metrostop.reminder

import com.metrostop.reminder.core.cell.CellZoneMap
import com.metrostop.reminder.core.cell.CellZoneTracker
import com.metrostop.reminder.core.cell.ZoneTransition
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
 * 站区映射 2026-10-08 修正批回归（节后首日双趟实测，硬性规则 2）。
 *
 * 三条修正（证据 = 10-08 双趟 lab/cell 流与停稳 MARK 逐点对齐；详见 `assets/cell_zones.json` note
 * 与 `docs/进度与交接.md` §四 10-08 条目）：
 * 1. **cd6_to_lanjiagou 陆肖 += `847:38656581633`**——原仅登记观东：10-08 晚列车在陆肖站台驻留
 *    该小区（18:46:47/49，MARK 18:46:46.4）→ 确认「观东站区」→ resync 跳过 10..11 →
 *    **提前 93s 误报「已到站」并自动结束**。补登后与观东合成**共享区间 11..12**：
 *    期望 10 → 重同步对齐 11（陆肖）；期望 12 ∈ 区间（观东到站仍可计数）。
 * 2. cd6_to_lanjiagou 张家寺 += `799:38656573441`（10-08 晚停稳后 2s 驻留；原仅存在于反向映射）。
 * 3. 市二医院**跨方向并集**（`701`/`820` 双方向互认）——10-08 早晚各驻留"反方向登记"的那个小区，
 *    导致当前方向站区确认迟到或缺失（早 4 号线市二医院计数迟 70s、晚 4 号线 0 计数）。
 */
class CellZones20261008FixTest {

    private val repo: LineRepository by lazy {
        LineRepository.parse(File("src/main/assets/subway_lines.json").readText())
            .getOrElse { throw AssertionError("线路资产解析失败：$it") }
    }

    private val zoneMap: CellZoneMap by lazy {
        CellZoneMap.parse(File("src/main/assets/cell_zones.json").readText())
            .getOrElse { throw AssertionError("站区映射解析失败：$it") }
    }

    private val config = TuningConfig.Default.copy(
        cellZoneGateEnabled = true,
        cellZoneScoutEnabled = true,
    )

    /** 玉双路 → 观东（6 号线 兰家沟方向，12 站） */
    private fun routeLanjiagou(): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", "cd6_to_lanjiagou") ?: throw AssertionError("找不到方向"),
        "cd6_s26",
        "cd6_s38",
    ) ?: throw AssertionError("路线构造失败")

    private fun lanjiagouLine() =
        zoneMap.forRoute(routeLanjiagou()) ?: throw AssertionError("路线取映射失败")

    @Test
    fun `847共享区间_陆肖观东双登记_解析为11之12`() {
        val line = lanjiagouLine()
        val luXiao = line.stations.first { it.stationId == "cd6_s37" }
        val guanDong = line.stations.first { it.stationId == "cd6_s38" }
        assertTrue("陆肖应登记 847（10-08 晚站台驻留证据）", "847:38656581633" in luXiao.cells)
        assertTrue("观东应保留 847（09-30 晚到站前主服务证据）", "847:38656581633" in guanDong.cells)

        // 0 串站守卫：847 只允许出现在陆肖 / 观东两站
        val hits = line.stations.filter { "847:38656581633" in it.cells }.map { it.stationId }
        assertEquals(listOf("cd6_s37", "cd6_s38"), hits)

        // 跟踪器：连续两拍 → 确认共享区间 11..12
        val tracker = CellZoneTracker(line, config)
        assertEquals(emptyList<ZoneTransition>(), tracker.onCell(1000, "847:38656581633", emptyList()))
        assertEquals(
            listOf<ZoneTransition>(ZoneTransition.Entered(2000, 11..12)),
            tracker.onCell(2000, "847:38656581633", emptyList()),
        )
    }

    @Test
    fun `张家寺_799补登_与905同站解析为10`() {
        val line = lanjiagouLine()
        val zhangJiaSi = line.stations.first { it.stationId == "cd6_s36" }
        assertEquals(listOf("905:38656774146", "799:38656573441"), zhangJiaSi.cells)

        val tracker = CellZoneTracker(line, config)
        tracker.onCell(1000, "799:38656573441", emptyList())
        assertEquals(
            listOf<ZoneTransition>(ZoneTransition.Entered(2000, 10..10)),
            tracker.onCell(2000, "799:38656573441", emptyList()),
        )
    }

    @Test
    fun `市二医院跨方向并集_701与820双方向互认`() {
        // 万盛方向：玉双路 → 太升南路（经市二医院）
        val wansheng = zoneMap.forRoute(
            RouteSpec.of(
                repo.line("cd4")!!,
                repo.direction("cd4", "cd4_to_wansheng")!!,
                "cd4_s22",
                "cd4_s20",
            )!!,
        ) ?: throw AssertionError("万盛方向取映射失败")
        // 西河方向：太升南路 → 玉双路（经市二医院）
        val xihe = zoneMap.forRoute(
            RouteSpec.of(
                repo.line("cd4")!!,
                repo.direction("cd4", "cd4_to_xihe")!!,
                "cd4_s20",
                "cd4_s22",
            )!!,
        ) ?: throw AssertionError("西河方向取映射失败")

        for (line in listOf(wansheng, xihe)) {
            val station = line.stations.first { it.stationId == "cd4_s21" }
            assertTrue("${line.directionId} 市二医院应含 701", "701:38658715650" in station.cells)
            assertTrue("${line.directionId} 市二医院应含 820", "820:38658711555" in station.cells)
        }
    }

    @Test
    fun `847确认于陆肖_期望10重同步对齐11_不跳12不报到达`() {
        val t0 = 100_000L
        val session = MonitorSession(routeLanjiagou(), config, lanjiagouLine())
        val events = ArrayList<DetectorEvent>()
        events += session.start(t0).events

        fun feed(fromMs: Long, toMs: Long, vib: Float) {
            var t = fromMs
            while (t <= toMs) {
                events += session.tick(Features(t, h = 0.01f, vib = vib)).events
                t += 1000L
            }
        }

        feed(t0 + 1_000L, t0 + 101_000L, vib = 0.3f) // 巡航（乘车带证据 + 已起步）
        // 直接推到「期望 10 = 张家寺」：本用例只关心 847/陆肖段语义，前 9 站用纠错跳过
        events += session.correctUp(t0 + 102_000L, by = 9).events
        events += session.onCellSample(t0 + 110_000L, "847:38656581633", emptyList())
        events += session.onCellSample(t0 + 111_000L, "847:38656581633", emptyList()) // 2 拍 → 区间 11..12
        feed(t0 + 112_000L, t0 + 180_000L, vib = 0.3f)
        feed(t0 + 181_000L, t0 + 195_000L, vib = 0.005f) // 停稳静默 → 候选（距纠错锚点 >60s）

        assertTrue(
            "应确认共享区间 11..12: $events",
            events.any { it.type == DetectorEventType.ZONE_CONFIRMED && it.note == "range=11..12" },
        )
        val arrivals = events.filter { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals("应恰好一次到站事件: ${arrivals.map { it.stationName }}", 1, arrivals.size)
        assertEquals("重同步应对齐区间首站（陆肖），不得跳到观东", "陆肖", arrivals[0].stationName)
        assertTrue(
            "应带重同步注记: ${arrivals[0].note}",
            "resync=10..10_skipped" in (arrivals[0].note ?: ""),
        )
        assertEquals(11, session.stationCount)
        assertTrue(
            "不得提前发「已到站」: ${events.filter { it.type == DetectorEventType.ALERT_ARRIVED }}",
            events.none { it.type == DetectorEventType.ALERT_ARRIVED },
        )
        assertTrue(
            "D−1（n=11 或进含 11 的区间）应已发",
            events.any { it.type == DetectorEventType.ALERT_PREV },
        )
    }

    @Test
    fun `847共享区间_离站重进区后侦察补检_目的站计数12并报到达`() {
        val t0 = 100_000L
        val session = MonitorSession(routeLanjiagou(), config, lanjiagouLine())
        val events = ArrayList<DetectorEvent>()
        events += session.start(t0).events

        fun feed(fromMs: Long, toMs: Long, vib: Float) {
            var t = fromMs
            while (t <= toMs) {
                events += session.tick(Features(t, h = 0.01f, vib = vib)).events
                t += 1000L
            }
        }
        fun cell(tMs: Long, main: String) {
            events += session.onCellSample(tMs, main, emptyList())
        }

        feed(t0 + 1_000L, t0 + 101_000L, vib = 0.3f)
        events += session.correctUp(t0 + 102_000L, by = 9).events // 期望 10 = 张家寺
        cell(t0 + 110_000L, "847:38656581633")
        cell(t0 + 111_000L, "847:38656581633") // → 共享区间 11..12
        feed(t0 + 112_000L, t0 + 180_000L, vib = 0.3f)
        feed(t0 + 181_000L, t0 + 195_000L, vib = 0.005f) // 陆肖停稳 → 计数 11（resync）
        assertEquals("陆肖应已计入", 11, session.stationCount)

        // 离站：未登记小区 → 退区；随后 847 重新进区（新一次区转移 = 目的站进近）
        feed(t0 + 196_000L, t0 + 200_000L, vib = 0.3f)
        cell(t0 + 201_000L, "999:999")
        cell(t0 + 205_000L, "847:38656581633")
        cell(t0 + 206_000L, "847:38656581633") // Entered 11..12（计数之后）
        feed(t0 + 207_000L, t0 + 260_000L, vib = 0.3f) // 进近巡航 53 s（> 间隔门槛）
        feed(t0 + 261_000L, t0 + 305_000L, vib = 0.005f) // 目的站静稳 → 侦察补检计数 12

        val arrivals = events.filter { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals(
            "到站序列: ${arrivals.map { "${it.stationName}@${it.tMs - t0}(${it.note})" }}",
            listOf("陆肖", "观东"),
            arrivals.map { it.stationName },
        )
        assertEquals(12, session.stationCount)
        assertTrue(
            "目的站到站提醒（ALERT_ARRIVED）应发出: $events",
            events.any { it.type == DetectorEventType.ALERT_ARRIVED },
        )
        assertTrue(
            "数满自动结束",
            events.any { it.type == DetectorEventType.MONITOR_END },
        )
    }
}
