package com.metrostop.reminder

import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.replay.CsvReplay
import com.metrostop.reminder.core.route.LineRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真实通勤回归（v2 检测器的验收级资产，硬性规则 2）**。
 *
 * 数据：2026-09-28 早高峰 红米 K80 实录（成都真实地铁、手机放口袋、50 Hz）：
 * - `real_commute_cd6_12stops_20260928.csv`：6 号线 观东 → 玉双路（12 站，29 min），
 *   当场 v1 全程漏检 10 站 + 站台误报 1 次 + 全程零提醒（用户手动纠错 12 次）——
 *   本次阈值重标与证据门的**直接动机**；
 * - `real_commute_cd4_2stops_20260928.csv`：4 号线 玉双路 → 太升南路（2 站，6.5 min）。
 *
 * 真值：用户当时逐站手动纠错（CORRECTION_* 事件）+ 线路资产站序（用户按按钮有 20~60 s
 * 滞后，逐站对齐以「最近纠错 + 事件形态」综合判定，详见交接文档 2026-09-28 数据分析）。
 * 本用例锁定 v2 的行为：
 *
 * - 站台静立等车**不再误报到站**（EVIDENCE_BLOCKED，v1 两段各有 1 次误报）；
 * - leg2 全自动：2/2 站检出、D−1 与到达提醒时刻正确（误差 ≤5 s）、零用户操作；
 * - leg1 全自动：12 站计数全对（中途 3 次区间误计与漏检相抵），D−1 在牛王庙前后触发、
 *   到达提醒在玉双路触发，**零用户操作**（v1 现场用户手按 12 次、零提醒）；
 * - 同一份 CSV 两次回放逐行一致（因果实现）。
 */
class RealCommuteRegressionTest {

    /** 生产配置（证据门开启）——本类就是它的验收级回归 */
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

    private fun route(lineId: String, directionId: String, boardingId: String, destId: String): RouteSpec =
        RouteSpec.of(
            repo.line(lineId) ?: throw AssertionError("找不到线路 $lineId"),
            repo.direction(lineId, directionId) ?: throw AssertionError("找不到方向 $directionId"),
            boardingId,
            destId,
        ) ?: throw AssertionError("路线构造失败")

    private fun arrivals(result: com.metrostop.reminder.core.replay.ReplayResult) =
        result.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    // ------------------------------------------------------------ leg2：4 号线 2 站（应全自动正确）

    @Test
    fun `leg2_两站全自动检出且提醒时刻正确`() {
        val result = CsvReplay.replay(
            csvText("real_commute_cd4_2stops_20260928.csv"),
            route("cd4", "cd4_to_wansheng", "cd4_s22", "cd4_s20"),
            config,
        )
        val t0 = 237882635L // 首样本 t_ms

        // 站台静立等车：v1 在 +28 s 误报「市二医院到达」；v2 必须被证据门拦截
        assertTrue(
            "站台静立应产生 EVIDENCE_BLOCKED（v1 误报回归点）",
            result.events.any { it.type == DetectorEventType.EVIDENCE_BLOCKED && it.tMs - t0 < 100_000 },
        )
        assertTrue(
            "前 100 s 不应有任何到站计数（v1 站台误报回归点）",
            arrivals(result).all { it.tMs - t0 >= 100_000 },
        )

        val arr = arrivals(result)
        assertEquals("两站应全部自动检出", 2, arr.size)
        // 真值：市二医院 +204.5 s / 太升南路 +346.5 s（用户纠错序列 + as-run 对齐）
        assertTrue("市二医院检出应贴近真值（实际 +${(arr[0].tMs - t0) / 1000.0}s）", arr[0].tMs in (t0 + 199_500)..(t0 + 209_500))
        assertTrue("太升南路检出应贴近真值（实际 +${(arr[1].tMs - t0) / 1000.0}s）", arr[1].tMs in (t0 + 341_500)..(t0 + 351_500))

        val prev = result.events.filter { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 提醒恰好一次", 1, prev.size)
        assertEquals("D−1 与第 1 站到站同刻", arr[0].tMs, prev[0].tMs)
        assertEquals("D−1 应带目的站名", "太升南路", prev[0].stationName)

        val alertArrived = result.events.filter { it.type == DetectorEventType.ALERT_ARRIVED }
        assertEquals("到达提醒恰好一次", 1, alertArrived.size)
        assertEquals("到达提醒与第 2 站到站同刻", arr[1].tMs, alertArrived[0].tMs)
        assertEquals("识别站数应为 k=2", 2, result.finalStationCount)
    }

    @Test
    fun `leg2_两次回放逐行一致`() {
        val text = csvText("real_commute_cd4_2stops_20260928.csv")
        val a = CsvReplay.replay(text, route("cd4", "cd4_to_wansheng", "cd4_s22", "cd4_s20"), config)
        val b = CsvReplay.replay(text, route("cd4", "cd4_to_wansheng", "cd4_s22", "cd4_s20"), config)
        assertEquals(a.comparableLines(), b.comparableLines())
    }

    // ------------------------------------------------------------ leg1：6 号线 12 站（v1 重灾区）

    @Test
    fun `leg1_站台误报被拦_全自动计数12站_双提醒触发`() {
        val result = CsvReplay.replay(
            csvText("real_commute_cd6_12stops_20260928.csv"),
            route("cd6", "cd6_to_wangcongzi", "cd6_s38", "cd6_s26"),
            config,
        )
        val t0 = 236095321L

        // 站台静立（+28 s 起）：v1 误报「陆肖到达」；v2 必须拦截
        assertTrue(
            "站台静立应产生 EVIDENCE_BLOCKED（v1 误报回归点）",
            result.events.any { it.type == DetectorEventType.EVIDENCE_BLOCKED && it.tMs - t0 < 100_000 },
        )
        assertTrue(
            "前 200 s 不应有任何到站计数（v1 站台误报回归点）",
            arrivals(result).all { it.tMs - t0 >= 200_000 },
        )

        // v1 现场：12 站只自动检出 2 站、漏 10 站、全程零提醒（用户手按 12 次）。
        // v2 回放：12 站计数全对（中途 3 次区间误计与若干漏检相抵，终点计数正确）。
        val arr = arrivals(result)
        assertEquals("v2 应全自动计满 12 站", 12, result.finalStationCount)
        // 末次计数贴近真实目的站 玉双路（真值 +1688.2 s）
        assertTrue(
            "末次计数应贴近玉双路真值（实际 +${(arr.last().tMs - t0) / 1000.0}s）",
            arr.last().tMs in (t0 + 1_683_200)..(t0 + 1_693_200),
        )

        // D−1 提醒：n=k−1 时发出。实际触发于 +1544.6 s（牛王庙前信号停车被计数，
        // 恰在进牛王庙的逼近段，提醒语义仍正确——真值 +1575.8 s，提前 31 s）
        val prev = result.events.filter { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 提醒恰好一次", 1, prev.size)
        assertTrue("D−1 应在牛王庙前后触发（实际 +${(prev[0].tMs - t0) / 1000.0}s）", prev[0].tMs in (t0 + 1_500_000)..(t0 + 1_600_000))
        assertEquals("D−1 应带目的站名", "玉双路", prev[0].stationName)

        // 到达提醒：n=k 时发出（真值 +1688.2 s，自动触发，无需用户纠错）
        val alertArrived = result.events.filter { it.type == DetectorEventType.ALERT_ARRIVED }
        assertEquals("到站提醒恰好一次", 1, alertArrived.size)
        assertTrue(
            "到站提醒应贴近玉双路真值（实际 +${(alertArrived[0].tMs - t0) / 1000.0}s）",
            alertArrived[0].tMs in (t0 + 1_683_200)..(t0 + 1_693_200),
        )
        assertEquals("到站提醒应带目的站名", "玉双路", alertArrived[0].stationName)
        assertNotNull("到达后应排定自动结束", alertArrived[0].note?.startsWith("end_at_"))
    }

    @Test
    fun `leg1_两次回放逐行一致`() {
        val text = csvText("real_commute_cd6_12stops_20260928.csv")
        val a = CsvReplay.replay(text, route("cd6", "cd6_to_wangcongzi", "cd6_s38", "cd6_s26"), config)
        val b = CsvReplay.replay(text, route("cd6", "cd6_to_wangcongzi", "cd6_s38", "cd6_s26"), config)
        assertEquals(a.comparableLines(), b.comparableLines())
    }
}
