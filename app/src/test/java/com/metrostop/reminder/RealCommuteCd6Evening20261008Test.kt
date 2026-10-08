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
 * **10-08 晚 6 号线实测回放（节后首日趟，847 串站修正锁定）**。
 *
 * 数据：2026-10-08 晚通勤 cd6 段（玉双路 → 观东 12 站，App 全程后台）。蜂窝主链路塌缩
 * （33 行 / 26 分钟 ≈ 1 拍/47 s）→ 顺江路/琉三路/金石路/张家寺 四站无站区确认（靠重同步跳过），
 * 现场计数 9/12 后**在陆肖站台误判到达**。
 *
 * MARK 真值（lab `enter_train_stop`，与传感器 `t_ms` 同源）：张家寺 `1138270185`（#14）、
 * 陆肖 `1138429328`（#15，18:46:46.418）、观东 `1138532803`（#16，18:48:29.893）；
 * 主链路 847 采样 = 18:46:47.160 / 18:46:49.841（陆肖站台）、18:48:17.319 / 18:48:19.582
 * （观东站台）→ **同一小区服务相邻两站**。
 *
 * 现场 vs 现资产（同一趟数据）：
 * | | 现场（847 仅登记观东） | 现资产（847 = 陆肖+观东，共享 11..12） |
 * |---|---|---|
 * | 18:46:49.843 进区 | 观东 12..12 → `dest_zone_entered` | 陆肖~观东 11..12 → **D−1 提醒**（观东） |
 * | 18:46:56.896 静稳候选 | 计数 **n=12（resync=10..11_skipped）→ 提前 93 s 报「已到站」+ 自动结束** | 计数 **n=11（resync=10..10_skipped）= 陆肖，会话继续** |
 * | 观东（18:48:29.9） | 已在 93 s 前误报结束 | 录制止于 18:47:26（现场自动结束所致）→ 本文件无法覆盖目的站段 |
 *
 * 目的站段（共享区间内，离站重进区后由放宽静稳侦察补检）由 [CellZones20261008FixTest]
 * 与 09-30 晚跨趟回放（[CrossTripGeneralizationTest]）覆盖。
 */
class RealCommuteCd6Evening20261008Test {

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

    /** 玉双路 → 观东（6 号线 兰家沟方向，12 站） */
    private fun route(): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", "cd6_to_lanjiagou") ?: throw AssertionError("找不到方向"),
        "cd6_s26", // 玉双路（上车站）
        "cd6_s38", // 观东
    ) ?: throw AssertionError("路线构造失败")

    private fun text(name: String): String =
        File("src/test/resources/replay/$name").readText()

    private fun replay(zoneLine: CellZoneLine): ReplayResult =
        CsvReplay.replay(
            text("real_commute_cd6_12stops_evening_20261008.csv"),
            route(),
            v4Config,
            cellCsv = text("real_commute_cd6_12stops_evening_cells_20261008.csv"),
            zoneLine = zoneLine,
        )

    /** 现资产（847 = 陆肖 + 观东 → 共享区间 11..12） */
    private fun unionLine(): CellZoneLine {
        val line = zoneMap.forRoute(route()) ?: throw AssertionError("路线取映射失败")
        val luXiao = line.stations.first { it.stationId == "cd6_s37" }
        val guanDong = line.stations.first { it.stationId == "cd6_s38" }
        assertTrue("资产回归：陆肖应含 847（10-08 晚站台驻留）", "847:38656581633" in luXiao.cells)
        assertTrue("资产回归：观东应含 847", "847:38656581633" in guanDong.cells)
        return line
    }

    /** 剥掉陆肖的 847 = 现场映射（847 仅登记观东 → 单站区间 12..12） */
    private fun fieldLine(): CellZoneLine {
        val line = unionLine()
        return line.copy(
            stations = line.stations.map { st ->
                if (st.stationId == "cd6_s37") {
                    st.copy(cells = st.cells - "847:38656581633")
                } else {
                    st
                }
            },
        )
    }

    private val markLuXiao = 1138429328L // MARK #15 陆肖 18:46:46.418
    private val fieldFalseArrivalMs = 1138439806L // 现场误报「已到站」（观东 n=12）18:46:56.896 = 陆肖停稳 +10.478s

    private fun arrivals(r: ReplayResult) =
        r.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    private fun timeline(r: ReplayResult) =
        r.events.joinToString("\n") {
            "${it.type.name.padEnd(16)} ${it.tMs} (${it.stationName}) ${it.note ?: ""}"
        }

    @Test
    fun `现资产_陆肖止步不误报到达_D减1提醒在进区时发出`() {
        val r = replay(unionLine())

        // 1. 847 确认 = 共享区间 11..12（修正核心）
        assertTrue(
            "应确认共享区间 11..12（陆肖~观东）\n${timeline(r)}",
            r.events.any {
                it.type == DetectorEventType.ZONE_CONFIRMED && it.note == "range=11..12"
            },
        )

        // 2. 到站序列与现场一致（稀疏流四站靠重同步跳过），末站止于陆肖
        val arr = arrivals(r)
        assertEquals(
            "到站序列: ${arr.map { "${it.stationName}@${it.tMs}" }}\n${timeline(r)}",
            listOf("牛王庙", "三官堂", "东光", "琉璃场", "金融城东", "中和", "陆肖"),
            arr.map { it.stationName },
        )
        assertEquals(11, r.finalStationCount)
        val luXiao = arr.last()
        assertTrue(
            "陆肖应带重同步注记（张家寺 10 无站区确认被跳过）: ${luXiao.note}",
            "resync=10..10_skipped" in (luXiao.note ?: ""),
        )
        val devSec = (luXiao.tMs - markLuXiao) / 1000.0
        assertTrue("陆肖到站偏差 ${devSec}s 超出 ±30s（现场同点 +10.5s）", Math.abs(devSec) <= 30.0)

        // 3. 不得提前报「已到站」（现场在 1138466806 = 陆肖停稳 +10.5s 误报）
        assertTrue(
            "修正后不得在陆肖站台报到达\n${timeline(r)}",
            r.events.none { it.type == DetectorEventType.ALERT_ARRIVED },
        )

        // 4. 进区即发 D−1 提醒（共享区间含 11 = 陆肖，下一站即目的站观东）
        val prev = r.events.filter { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 提醒恰好一条: ${prev.map { "${it.stationName}@${it.tMs}" }}", 1, prev.size)
        assertEquals("观东", prev[0].stationName)
        assertTrue(
            "D−1 应早于陆肖停稳计数: ${prev[0].tMs - markLuXiao}ms",
            prev[0].tMs < markLuXiao + 10_000L,
        )

        // 5. 共享目的站区不发「即将到站」（与进区瞬间的 D−1 同点，避免重复）
        assertTrue(
            "共享区不得发「即将到站」: ${r.events.filter { it.type == DetectorEventType.ALERT_DEST_SOON }}",
            r.events.none { it.type == DetectorEventType.ALERT_DEST_SOON },
        )

        // 6. 录制结束时仍在监测（现场是误报后自动结束；观东 MARK 18:48:29.9 超出本文件）
        assertTrue("应正常收尾\n${timeline(r)}", r.events.any { it.type == DetectorEventType.MONITOR_END })
    }

    @Test
    fun `现场映射_847仅观东_复现提前93s误报到达`() {
        val r = replay(fieldLine())

        val arr = arrivals(r)
        val last = arr.last()
        assertEquals("现场末次「到站」应误判为观东（目的站）\n${timeline(r)}", "观东", last.stationName)
        assertEquals(12, r.finalStationCount)
        assertTrue(
            "现场误报应带 resync=10..11_skipped: ${last.note}",
            "resync=10..11_skipped" in (last.note ?: ""),
        )
        val devSec = (last.tMs - markLuXiao) / 1000.0
        assertTrue(
            "误报时刻应复现现场（陆肖停稳 +10.5s，偏差 ${devSec}s）",
            Math.abs(last.tMs - fieldFalseArrivalMs) <= 2_000L,
        )
        assertTrue(
            "误报即触发「已到站」+ 自动结束（现场表现）",
            r.events.any { it.type == DetectorEventType.ALERT_ARRIVED },
        )
    }
}
