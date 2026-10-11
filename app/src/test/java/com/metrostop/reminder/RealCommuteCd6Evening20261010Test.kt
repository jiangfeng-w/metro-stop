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
 * **2026-10-10 C1 首验日 6 号线双趟实测回放（空样本平台门修复锁定）**。
 *
 * 背景（数据依据见仓库外 `analysis/FINDINGS-2026-10-10.md`）：C1 主动轮询后拍点密度达标
 * （主链路 3 s/拍），但 App 后台时平台「回调来、内容空」——主链路空行率 51~86%（历史 0~2%）。
 * 旧实现把空样本当「不命中任何站区 → 立即退区」，站区被切碎成 6~12 s，与 IMU 候选相位错位：
 * 现场晚 6 号线仅 2 站有效计数（连漏 7 站）、早 6 号线三官堂/顺江路被 resync 吞掉。
 *
 * 修复两点（2026-10-10 夜，判定路径经用户拍板）：
 * 1. `CellZoneTracker`：**空样本（无主服务且无邻区）保持当前站区与候选**（「无数据 ≠ 已离开」），
 *    离站只由非空样本不命中判定；
 * 2. `MonitorSession` 侦察：STOPPED 下允许「本区尚未计数（`confirmedAtMs > lastCountedMs`）」
 *    时放行放宽静稳补检，堵「候选先于区确认被拦 → STOPPED → 侦察被挡」的双卡点。
 *
 * MARK 真值 = lab `enter_train_stop`（t_ms 同源，含 C1 采样）；能量化对照：
 * | 会话 | 基线（修复前） | 修复后（本文件锁定） |
 * |---|---|---|
 * | 晚·6号线 玉双路→观东 | 仅 2 次计数（连漏 2..8、末两站错位） | **12/12、正站 +7~15 s（末站 −11 s）** |
 * | 早·6号线 观东→玉双路 | 7 次计数（三官堂/顺江路被 resync 吞） | **9/12、三官堂 +6.0 s / 顺江路 +7.8 s 恢复** |
 */
class RealCommuteCd6Evening20261010Test {

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

    private fun route(direction: String, from: String, to: String): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", direction) ?: throw AssertionError("找不到方向 $direction"),
        from,
        to,
    ) ?: throw AssertionError("路线构造失败")

    private fun replay(name: String, cellName: String, r: RouteSpec): ReplayResult {
        val zoneLine: CellZoneLine = zoneMap.forRoute(r) ?: throw AssertionError("路线取映射失败")
        return CsvReplay.replay(
            text(name),
            r,
            v4Config,
            cellCsv = text(cellName),
            zoneLine = zoneLine,
        )
    }

    private fun arrivals(r: ReplayResult) =
        r.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    private fun timeline(r: ReplayResult) = r.events.joinToString("\n") {
        "${it.type.name.padEnd(16)} ${it.tMs} (${it.stationName}) ${it.note ?: ""}"
    }

    // ---------------- 晚·6号线 玉双路→观东（MARK：牛王庙…观东 12 站） ----------------

    private val eveningMarks = linkedMapOf(
        "牛王庙" to 1310123562L, "顺江路" to 1310249569L, "三官堂" to 1310352994L,
        "东光" to 1310480696L, "琉璃场" to 1310615743L, "琉三路" to 1310723078L,
        "金石路" to 1310869889L, "金融城东" to 1311007085L, "中和" to 1311209847L,
        "张家寺" to 1311358233L, "陆肖" to 1311496871L, "观东" to 1311602044L,
    )

    private fun eveningReplay() = replay(
        "real_commute_cd6_12stops_evening_20261010.csv",
        "real_commute_cd6_12stops_evening_cells_20261010.csv",
        route("cd6_to_lanjiagou", "cd6_s26", "cd6_s38"),
    )

    @Test
    fun `晚6_空样本修复后_12站全对_误差正负20秒内`() {
        val r = eveningReplay()
        val arr = arrivals(r)

        assertEquals("应计满 12 站: ${arr.map { it.stationName }}\n${timeline(r)}", 12, r.finalStationCount)
        assertEquals(
            "站名序列应与真值一致: ${arr.map { it.stationName }}",
            eveningMarks.keys.toList(),
            arr.map { it.stationName },
        )

        // 逐站误差（现场为连漏 7 站；修复后全部 ±20 s 内——放宽到 20 s 容 git 环境差异）
        val deviations = eveningMarks.entries.map { (name, mark) ->
            val a = arr.first { it.stationName == name }
            name to (a.tMs - mark) / 1000.0
        }
        val bad = deviations.filter { Math.abs(it.second) > 20.0 }
        assertTrue("全站误差应在 ±20 s 内（基线连漏 7 站），实际越界: $bad\n${timeline(r)}", bad.isEmpty())

        // 站区安静窗口内的确认计数（zone_still）：修复后的主力路径
        val zoneStill = arr.count { (it.note ?: "").startsWith("zone_still") }
        assertTrue("zone_still 应为主力（修复后 11 站），实际 $zoneStill", zoneStill >= 10)
    }

    @Test
    fun `晚6_修复后_共享区间语义正确_不提前误报到达`() {
        val r = eveningReplay()

        // 陆肖站台（847 主服务）确认共享区间 11..12 + D−1 提醒（观东）
        assertTrue(
            "应确认共享区间 11..12\n${timeline(r)}",
            r.events.any { it.type == DetectorEventType.ZONE_CONFIRMED && it.note == "range=11..12" },
        )
        val prev = r.events.filter { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 恰好一条", 1, prev.size)
        assertEquals("观东", prev[0].stationName)

        // 「已到站」只在真观东发出（修复前基线：止步 n=11 或误报）
        val arrived = r.events.filter { it.type == DetectorEventType.ALERT_ARRIVED }
        assertEquals("到达提醒恰好一条", 1, arrived.size)
        assertEquals("观东", arrived[0].stationName)
        val guanDongMark = eveningMarks.getValue("观东")
        assertTrue(
            "到达提醒应在观东 MARK 附近（±30 s）",
            Math.abs(arrived[0].tMs - guanDongMark) <= 30_000L,
        )
    }

    // ---------------- 早·6号线 观东→玉双路（三官堂/顺江路双卡恢复锁定） ----------------

    private val morningMarks = linkedMapOf(
        "陆肖" to 1273111998L, "张家寺" to 1273216051L, "中和" to 1273360062L,
        "金融城东" to 1273599120L, "金石路" to 1273702145L, "琉三路" to 1273855587L,
        "琉璃场" to 1273960990L, "东光" to 1274094130L, "三官堂" to 1274225235L,
        "顺江路" to 1274334625L, "牛王庙" to 1274459097L, "玉双路" to 1274584710L,
    )

    private fun morningReplay() = replay(
        "real_commute_cd6_12stops_morning_20261010.csv",
        "real_commute_cd6_12stops_morning_cells_20261010.csv",
        route("cd6_to_wangcongzi", "cd6_s38", "cd6_s26"),
    )

    @Test
    fun `早6_空样本修复后_三官堂与顺江路恢复计数_相位错位双卡解除`() {
        val r = morningReplay()
        val arr = arrivals(r)
        val names = arr.map { it.stationName }

        // 修复前：三官堂/顺江路被 resync 吞掉（东光 resync=6..7、牛王庙 resync=9..10）；
        // 修复后：两站独立计数（zone_still），resync 只发生在金融城东→金石路段（4..4）
        assertTrue("三官堂应独立计数（修复前被吞）: $names\n${timeline(r)}", "三官堂" in names)
        assertTrue("顺江路应独立计数（修复前被吞）: $names\n${timeline(r)}", "顺江路" in names)

        val sanGuan = arr.first { it.stationName == "三官堂" }
        val shunJiang = arr.first { it.stationName == "顺江路" }
        assertTrue(
            "三官堂误差应在 ±20 s: ${(sanGuan.tMs - morningMarks.getValue("三官堂")) / 1000.0}s",
            Math.abs(sanGuan.tMs - morningMarks.getValue("三官堂")) <= 20_000L,
        )
        assertTrue(
            "顺江路误差应在 ±20 s: ${(shunJiang.tMs - morningMarks.getValue("顺江路")) / 1000.0}s",
            Math.abs(shunJiang.tMs - morningMarks.getValue("顺江路")) <= 20_000L,
        )
    }

    @Test
    fun `早6_修复后_牛王庙D减1与玉双路即将到达提醒在位`() {
        val r = morningReplay()

        val prev = r.events.filter { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 恰好一条（牛王庙区进区触发）", 1, prev.size)
        assertEquals("玉双路", prev[0].stationName)

        val soon = r.events.filter { it.type == DetectorEventType.ALERT_DEST_SOON }
        assertEquals("「即将到达」恰好一条", 1, soon.size)
        assertEquals("玉双路", soon[0].stationName)

        // 修复基线：玉双路无计数无到达（停站高振动 + 区窗被空行打碎）；
        // 修复后区窗保持 → 「即将到达」在到站时刻附近发出（本会话为手动结束，无自动到达）
        val yuShuangMark = morningMarks.getValue("玉双路")
        assertTrue(
            "「即将到达」应在玉双路 MARK ±30 s: ${(soon[0].tMs - yuShuangMark) / 1000.0}s",
            Math.abs(soon[0].tMs - yuShuangMark) <= 30_000L,
        )
    }
}
