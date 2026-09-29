package com.metrostop.reminder

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
 * **早通勤站姿回归（v4 验收级资产，硬性规则 2）**。
 *
 * 数据：2026-09-29 早高峰 红米 K80 实录（成都 6 号线 观东 → 玉双路，12 站，晨高峰站姿/坐姿混合，
 * `real_commute_cd6_12stops_morning_20260929.csv` + 小区流 `..._cells_...csv`，t0=322327723 ms）。
 * 现场 v3 表现（同批 events）：12 站中 5 站干净自动、5 站静默漏检、3 次区间信号停车误计、按键 9 次；
 * 真实停稳时刻 7/12 显示「巡航中」。数据依据与裁决见
 * `docs/spec/active/cell-zone-detector-v4/需求.md` 与 `cellular-wifi-fingerprint-validate/需求.md` 第八节。
 *
 * 真值（12 个「列车进站停稳」MARK，用户确认误差 ≤15 s，会话相对秒）：
 * ```
 * 陆肖+345.0 张家寺+470.7 中和+622.7 金融城东+825.5 金石路+980.5 琉三路+1122.1
 * 琉璃场+1244.3 东光+1377.0 三官堂+1525.0 顺江路+1635.3 牛王庙+1750.6 玉双路+1880.2
 * ```
 *
 * v4 行为（本用例锁定，自洽级验收 = 需求验收标准 2）：
 * - 12/12 计数正确且站名序列与真值一致（站区门 + 侦察补检 + 漏检重同步）；
 * - **0 次信号停车误计**：3 次区外停顿（+520.2 / +1322.7 等）被站区门拦为 ZONE_SUPPRESSED；
 * - D−1 在进入牛王庙站区时提前触发；到站提醒在玉双路；
 * - 降级：默认配置（影子模式）= v3 行为；晚通勤方向无映射 → 12/12 保持（不劣化）。
 */
class RealCommuteMorningRegressionTest {

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

    private fun text(name: String): String =
        File("src/test/resources/replay/$name").readText()

    /** 观东 → 玉双路（6 号线 望丛祠方向，12 站） */
    private fun cd6Route(): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", "cd6_to_wangcongzi") ?: throw AssertionError("找不到方向"),
        "cd6_s38", // 观东
        "cd6_s26", // 玉双路
    ) ?: throw AssertionError("路线构造失败")

    /** 玉双路 → 太升南路（4 号线 万盛方向，2 站，共享小区区） */
    private fun cd4Route(): RouteSpec = RouteSpec.of(
        repo.line("cd4") ?: throw AssertionError("找不到线路 cd4"),
        repo.direction("cd4", "cd4_to_wansheng") ?: throw AssertionError("找不到方向"),
        "cd4_s22", // 玉双路
        "cd4_s20", // 太升南路
    ) ?: throw AssertionError("路线构造失败")

    private val expectedStations = listOf(
        "陆肖", "张家寺", "中和", "金融城东", "金石路", "琉三路",
        "琉璃场", "东光", "三官堂", "顺江路", "牛王庙", "玉双路",
    )

    private fun arrivals(r: ReplayResult) = r.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    @Test
    fun `早通勤_v4自洽_12站全检出且零信号停车误计`() {
        val route = cd6Route()
        val result = CsvReplay.replay(
            text("real_commute_cd6_12stops_morning_20260929.csv"),
            route,
            v4Config,
            cellCsv = text("real_commute_cd6_12stops_morning_cells_20260929.csv"),
            zoneLine = zoneMap.forRoute(route),
        )

        // 1. 计数 = 12（一次不多一次不少：3 次信号停车全部被站区门拦截）
        assertEquals(
            "到站候选序列: ${arrivals(result).map { "${it.stationName}@${it.note}" }}",
            12,
            result.finalStationCount,
        )
        assertEquals(12, arrivals(result).size)

        // 2. 站名序列与真值一致（站区身份驱动的计数不可错位）
        assertEquals(expectedStations, arrivals(result).map { it.stationName })

        // 3. 提醒：D−1 与到站都发（v4 的 D−1 来自进牛王庙站区，提前于停稳）
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_PREV })
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_ARRIVED })

        // 4. 信号停车误计免疫：区外候选被拦为 ZONE_SUPPRESSED（今早 3 次误计在 v3 均被计入）
        assertTrue(
            "应有区外候选被拦（今早 +520.2/+926.4/+1322.7 三次误计的来源）",
            result.events.count { it.type == DetectorEventType.ZONE_SUPPRESSED } >= 2,
        )

        // 5. 首个计数不得早于首站真值前 90 s（上车站区不计数）
        val t0 = 322327723L
        assertTrue(
            "首站计数过早：${(arrivals(result).first().tMs - t0) / 1000.0}s",
            arrivals(result).first().tMs - t0 >= 255_000,
        )
    }

    @Test
    fun `早通勤4号线_共享小区区_2站全检出`() {
        val route = cd4Route()
        val result = CsvReplay.replay(
            text("real_commute_cd4_2stops_morning_20260929.csv"),
            route,
            v4Config,
            cellCsv = text("real_commute_cd4_2stops_morning_cells_20260929.csv"),
            zoneLine = zoneMap.forRoute(route),
        )
        // 701:38658715650 一段覆盖市二医院+太升南路（位置区间 1..2）：
        // 区内两站靠 IMU 静稳逐站推进，站区只提供「已进入该段」的身份
        assertEquals(2, result.finalStationCount)
        assertEquals(listOf("市二医院", "太升南路"), arrivals(result).map { it.stationName })
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_PREV })
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_ARRIVED })
    }

    @Test
    fun `早通勤_影子模式降级_等于v3基线`() {
        // 默认配置（两开关 false）+ 映射在场：判定行为必须与「无 v4」完全一致（红线安全）
        val route = cd6Route()
        val withMapShadow = CsvReplay.replay(
            text("real_commute_cd6_12stops_morning_20260929.csv"),
            route,
            TuningConfig.Default,
            cellCsv = text("real_commute_cd6_12stops_morning_cells_20260929.csv"),
            zoneLine = zoneMap.forRoute(route),
        )
        val withoutV4 = CsvReplay.replay(
            text("real_commute_cd6_12stops_morning_20260929.csv"),
            route,
            TuningConfig.Default,
        )
        assertEquals(withoutV4.finalStationCount, withMapShadow.finalStationCount)
        assertEquals(
            withoutV4.events.filter { it.type == DetectorEventType.STATION_ARRIVED }.map { it.tMs },
            withMapShadow.events.filter { it.type == DetectorEventType.STATION_ARRIVED }.map { it.tMs },
        )
    }

    @Test
    fun `晚通勤_无映射方向_降级不劣化`() {
        // cd6_to_lanjiagou 方向无映射 → 跟踪器不激活 → v3 基线 12/12 保持
        val route = RouteSpec.of(
            repo.line("cd6")!!,
            repo.direction("cd6", "cd6_to_lanjiagou")!!,
            "cd6_s26", // 玉双路
            "cd6_s38", // 观东
        )!!
        val result = CsvReplay.replay(
            text("real_commute_cd6_12stops_evening_20260928.csv"),
            route,
            v4Config,
            cellCsv = null,
            zoneLine = zoneMap.forRoute(route),
        )
        assertEquals(12, result.finalStationCount)
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_PREV })
        assertTrue(result.events.any { it.type == DetectorEventType.ALERT_ARRIVED })
    }
}
