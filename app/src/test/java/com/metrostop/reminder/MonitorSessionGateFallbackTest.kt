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
 * 站区门活性回退回归（2026-09-29 夜，主通道切换前置保险）。
 *
 * 背景：gate 开 + 有映射 + 小区流缺席/断流时，站区门若不设活性会把全部候选拦死
 * （0 计数 0 提醒，2026-09-29 晚通勤回归暴露的 P0 缺口）。修复：最近一次 ZONE 转移
 * 超过 `cellZoneGateLivenessSec` → gate 旁路，判定回退 v3。
 *
 * 场景（cd4 玉双路→太升南路 2 站，合成时间线，liveness 缩短为 60 s 加速验证）：
 * - 小区流正常 → 进区确认 → 第 1 站在 gate 生效下计数；
 * - 之后小区流静默超过活性窗口 → 第 2 站候选**不被拦截**、按 v3 计数并触发到站提醒；
 * - 小区流从头缺席 → 全程等同 v3（gate 从未激活）。
 */
class MonitorSessionGateFallbackTest {

    private val repo: LineRepository by lazy {
        LineRepository.parse(File("src/main/assets/subway_lines.json").readText())
            .getOrElse { throw AssertionError("线路资产解析失败：$it") }
    }

    /** 玉双路 → 太升南路（4 号线万盛方向，2 站） */
    private fun cd4Route(): RouteSpec = RouteSpec.of(
        repo.line("cd4") ?: throw AssertionError("找不到线路 cd4"),
        repo.direction("cd4", "cd4_to_wansheng") ?: throw AssertionError("找不到方向"),
        "cd4_s22", // 玉双路
        "cd4_s20", // 太升南路
    ) ?: throw AssertionError("路线构造失败")

    /** 合成映射：市二医院=100:100、太升南路=200:200（站序与资产一致，直接注入会话层） */
    private fun zoneLine() = CellZoneLine(
        lineId = "cd4",
        directionId = "cd4_to_wansheng",
        stations = listOf(
            CellZoneStation("cd4_s21", listOf("100:100")),
            CellZoneStation("cd4_s20", listOf("200:200")),
        ),
    )

    private class Run(
        val session: MonitorSession,
        val events: List<DetectorEvent>,
        val t0: Long,
    )

    /**
     * 时间线（证据门要求首个候选前 150 s 窗内 ≥48% 乘车带，故巡航段 ≥100 s）：
     * 巡航 104 s → 进区确认（小区流 3 拍，实测进区早于停稳 0~58 s）→ 停站1 静稳 10 s
     * → 巡航 110 s（小区流静默超活性窗口）→ 停站2 静稳 10 s（gate 已过期 → 必须走 v3）。
     */
    private fun drive(feedCells: Boolean): Run {
        val t0 = 100_000L
        val config = TuningConfig.Default.copy(cellZoneGateLivenessSec = 60.0)
        val session = MonitorSession(cd4Route(), config, zoneLine())
        val events = ArrayList<DetectorEvent>()
        events += session.start(t0).events

        fun feed(fromMs: Long, toMs: Long, vib: Float) {
            var t = fromMs
            while (t <= toMs) {
                events += session.tick(Features(t, h = 0.01f, vib = vib)).events
                t += 1000L
            }
        }

        feed(t0 + 1_000L, t0 + 104_000L, vib = 0.3f) // 起步 + 巡航（预热内有振动 → 车上中途开始）
        if (feedCells) {
            repeat(3) { i ->
                events += session.onCellSample(t0 + 105_000L + 1000L * i, "100:100", emptyList())
            }
        }
        feed(t0 + 108_000L, t0 + 117_000L, vib = 0.005f) // 停站 1：gate 活性期内静稳计数
        feed(t0 + 118_000L, t0 + 227_000L, vib = 0.3f) // 巡航 110 s，小区流静默 > 活性窗口
        feed(t0 + 228_000L, t0 + 237_000L, vib = 0.005f) // 停站 2：gate 已过期 → 必须走 v3
        return Run(session, events, t0)
    }

    @Test
    fun `gate_小区流断流超活性窗口_第二站自动回退v3计数`() {
        val run = drive(feedCells = true)
        val arrivals = run.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

        assertEquals("两站都应计数（第 2 站不得被过期 gate 拦截）", 2, run.session.stationCount)
        assertEquals("到站候选恰好 2 次（重复候选被正常去重/拦截）", 2, arrivals.size)
        assertTrue(
            "第 1 站应在 gate 活性期内计数: ${arrivals.map { it.tMs - run.t0 }}",
            arrivals[0].tMs in (run.t0 + 108_000L)..(run.t0 + 117_000L),
        )
        assertTrue(
            "第 2 站应在活性过期后按 v3 计数: ${arrivals.map { it.tMs - run.t0 }}",
            arrivals[1].tMs >= run.t0 + 220_000L,
        )
        assertTrue(events(run, DetectorEventType.ALERT_ARRIVED).isNotEmpty())
        assertTrue(events(run, DetectorEventType.OVERSHOOT).isEmpty())
    }

    @Test
    fun `gate_小区流从头缺席_全程等同v3`() {
        val run = drive(feedCells = false)
        val arrivals = run.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

        assertEquals(
            "事件流: ${run.events.joinToString { "${it.type.name}@${it.tMs - run.t0}:${it.note}" }}",
            2,
            run.session.stationCount,
        )
        assertEquals(2, arrivals.size)
        assertTrue(events(run, DetectorEventType.ZONE_CONFIRMED).isEmpty())
        assertTrue(events(run, DetectorEventType.ZONE_SUPPRESSED).isEmpty())
        assertTrue(events(run, DetectorEventType.ALERT_ARRIVED).isNotEmpty())
    }

    private fun events(run: Run, type: DetectorEventType) =
        run.events.filter { it.type == type }
}
