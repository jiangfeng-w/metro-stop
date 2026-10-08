package com.metrostop.reminder

import com.metrostop.reminder.core.cell.CellZoneLine
import com.metrostop.reminder.core.cell.CellZoneMap
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.replay.CsvReplay
import com.metrostop.reminder.core.replay.ReplayResult
import com.metrostop.reminder.core.route.LineRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **10-08 早 4 号线实测回放（节后首日趟，修正批锁定）**。
 *
 * 数据：2026-10-08 早通勤 cd4 段（玉双路 → 太升南路 2 站，App 全程前台）。
 * MARK 真值（「列车进站停稳」，与传感器 `t_ms` 同源）：市二医院 `1102066036`、
 * 太升南路 `1102191992`（会话 t0 = `1101905476`）。
 *
 * 现场表现与复现（同一趟数据，两种资产对照）：
 * | 站点 | 现资产（701/820 跨方向并集） | 修订前（市二医院仅 701） |
 * |---|---|---|
 * | 市二医院站区确认（1..1） | 停稳前 4.3 s | 停稳后 63.2 s |
 * | 市二医院到站计数 | +27.2 s（zone_still） | **+70.4 s**（现场「计数迟 70 s」） |
 * | 太升南路到站计数 | −9.2 s（restill） | −9.2 s（restill） |
 * | 到站提醒 | 发出 | 发出（靠单站站区豁免兜底） |
 * | 两站计数间隔 | 89.5 s（> 门槛，自然通过） | 46.4 s（< 门槛 → 依赖豁免） |
 *
 * 现场 `STOP_SUSPECT interval<60s`（太升南路到站被拦、到站提醒丢失）的成因即「市二医院
 * 计数迟 70 s 把真实到站间隔压到 46 s」；两个修复各自独立止血——补登 820 把计数提前 43 s
 * （间隔回到 89.5 s），单站站区精确认豁免（`MonitorSession.translateArrival`）则在计数迟到时
 * 仍保住到站提醒（合成用例见 [MonitorSessionIntervalTest]）。本用例锁定**现资产**的两站全计数
 * 与提醒齐全，同时锁定「修订前资产必然计数迟 70 s」——防资产回退。
 */
class RealCommuteCd4Morning20261008Test {

    private val repo: LineRepository by lazy {
        LineRepository.parse(File("src/main/assets/subway_lines.json").readText())
            .getOrElse { throw AssertionError("线路资产解析失败：$it") }
    }

    private val zoneMap: CellZoneMap by lazy {
        CellZoneMap.parse(File("src/main/assets/cell_zones.json").readText())
            .getOrElse { throw AssertionError("站区映射解析失败：$it") }
    }

    private val v4Config = TuningConfig.Default.copy(
        cellZoneGateEnabled = true,
        cellZoneScoutEnabled = true,
    )

    /** 玉双路 → 太升南路（4 号线万盛方向，2 站） */
    private fun route(): RouteSpec = RouteSpec.of(
        repo.line("cd4") ?: throw AssertionError("找不到线路 cd4"),
        repo.direction("cd4", "cd4_to_wansheng") ?: throw AssertionError("找不到方向"),
        "cd4_s22", // 玉双路
        "cd4_s20", // 太升南路
    ) ?: throw AssertionError("路线构造失败")

    private fun text(name: String): String =
        File("src/test/resources/replay/$name").readText()

    private fun replay(zoneLine: CellZoneLine): ReplayResult =
        CsvReplay.replay(
            text("real_commute_cd4_2stops_morning_20261008.csv"),
            route(),
            v4Config,
            cellCsv = text("real_commute_cd4_2stops_morning_cells_20261008.csv"),
            zoneLine = zoneLine,
        )

    /** 现资产（市二医院 = 701 + 820 跨方向并集） */
    private fun unionLine(): CellZoneLine {
        val line = zoneMap.forRoute(route()) ?: throw AssertionError("路线取映射失败")
        val shop2 = line.stations.first { it.stationId == "cd4_s21" }
        assertTrue("资产回归：市二医院应含 820（10-08 早站台驻留补登）", "820:38658711555" in shop2.cells)
        assertTrue("资产回归：市二医院应含 701", "701:38658715650" in shop2.cells)
        return line
    }

    /** 剥掉 820 = 修订前映射（820 当时只登记在反方向 cd4_to_xihe） */
    private fun preFixLine(): CellZoneLine {
        val line = unionLine()
        return line.copy(
            stations = line.stations.map { st ->
                if (st.stationId == "cd4_s21") st.copy(cells = st.cells - "820:38658711555") else st
            },
        )
    }

    /** MARK 真值（停稳时刻，绝对 t_ms） */
    private val marks = mapOf("市二医院" to 1102066036L, "太升南路" to 1102191992L)

    private fun arrivals(r: ReplayResult) =
        r.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    private fun timeline(r: ReplayResult) =
        r.events.joinToString("\n") {
            "${it.type.name.padEnd(16)} ${it.tMs} (${it.stationName}) ${it.note ?: ""}"
        }

    @Test
    fun `现资产_两站全计数_三级提醒齐全_站区零误伤`() {
        val r = replay(unionLine())

        val arr = arrivals(r)
        assertEquals("到站序列: ${arr.map { it.stationName }}\n${timeline(r)}", 2, r.finalStationCount)
        assertEquals(listOf("市二医院", "太升南路"), arr.map { it.stationName })

        // 到站时刻偏差（实测：市二医院 +27.2 s、太升南路 −9.2 s）
        for (a in arr) {
            val mk = marks[a.stationName] ?: throw AssertionError("未知站名: ${a.stationName}")
            val devSec = (a.tMs - mk) / 1000.0
            assertTrue("${a.stationName} 到站偏差 ${devSec}s 超出 ±60s", Math.abs(devSec) <= 60.0)
        }

        assertTrue(
            "到站提醒（ALERT_ARRIVED）必须发出\n${timeline(r)}",
            r.events.any { it.type == DetectorEventType.ALERT_ARRIVED },
        )
        assertTrue("D−1 提醒", r.events.any { it.type == DetectorEventType.ALERT_PREV })
        assertTrue("即将到站提醒", r.events.any { it.type == DetectorEventType.ALERT_DEST_SOON })
        assertTrue("数满自动结束", r.events.any { it.type == DetectorEventType.MONITOR_END })
        assertTrue(
            "现资产不得再有间隔门槛误伤: ${r.events.filter { it.type == DetectorEventType.STOP_SUSPECT }}",
            r.events.none { it.type == DetectorEventType.STOP_SUSPECT },
        )

        // 两站站区都确认（市二医院靠 820、太升南路靠 918/884），且市二医院站区确认在停稳之前
        val confirms = r.events.filter { it.type == DetectorEventType.ZONE_CONFIRMED }
        assertTrue(
            "市二医院站区（1..1）应在停稳前确认: ${confirms.filter { it.note == "range=1..1" }}",
            confirms.any {
                it.note == "range=1..1" && it.tMs < marks.getValue("市二医院")
            },
        )
        assertTrue(
            "太升南路站区（2..2）应确认: ${confirms.filter { it.note == "range=2..2" }}",
            confirms.any { it.note == "range=2..2" },
        )
    }

    @Test
    fun `修订前资产_市二医院计数迟70s_下限守卫生效`() {
        val r = replay(preFixLine())
        val arr = arrivals(r)

        // 到站提醒本身由「单站站区精确认豁免」兜住 → 两站仍计满
        assertEquals("到站序列: ${arr.map { it.stationName }}\n${timeline(r)}", 2, r.finalStationCount)
        assertTrue(
            "到站提醒仍应由 zoneExact 豁免兜住",
            r.events.any { it.type == DetectorEventType.ALERT_ARRIVED },
        )

        // 但 820 缺失的代价：市二医院站区确认落到停稳之后、到站计数迟 70 s（现场值）
        val shop2 = arr.first { it.stationName == "市二医院" }
        val devSec = (shop2.tMs - marks.getValue("市二医院")) / 1000.0
        assertTrue(
            "修订前市二医院计数应迟 >60s（实测 +70.4s）: $devSec\n${timeline(r)}",
            devSec > 60.0,
        )
        val confirms = r.events.filter { it.type == DetectorEventType.ZONE_CONFIRMED }
        assertTrue(
            "修订前不得出现「停稳前确认的市二医院站区」: ${confirms.filter { it.note == "range=1..1" }}",
            confirms.none {
                it.note == "range=1..1" && it.tMs < marks.getValue("市二医院")
            },
        )
    }
}
