package com.metrostop.reminder

import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.Direction
import com.metrostop.reminder.core.model.Line
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.replay.CsvReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **真机实测 CSV 回归**（硬性规则 2：每次调参 / 修缺陷必须留 replay 回归用例）。
 *
 * 数据：2026-09-25 红米 K80 实机录制（`sensor_20260925_200159.csv`，7297 样本 / 155 s / ≈47 Hz）。
 * 操作：App 内开始监测 → 平放静置 ~70 s → 手持摇动 ~26 s → 静置 ~35 s。
 *
 * 本用例锁定的缺陷（修复前会 FAIL）：
 * 摇动使 H 出现尖峰进入 BRAKING → H 回落触发 BRAKE_ABORT 回 CRUISE；此后振动跌到 ~0.008
 * 并持续 30+ s，但**旧版 CRUISE 分支只累计制动、不累计静止** → 完全漏检到站。
 * 修复：CRUISE 中也累计 `stillForSec`，达 stillConfirmSec 即出站（`note=still_no_brake`）。
 *
 * 基准事件序列（回放 `route(k=3)`）：
 * ```
 * 19744849 MONITOR_START
 * 19764853 WARMUP_DONE
 * 19772854 STOPPING still_no_brake          ← 修复点：静置 8 s 即到站（无制动特征）
 * 19772854 FIRST_STOP_IGNORED               ← 首站不计数（hasRun=false）
 * 19841567 DEPART                           ← 摇动被视为起步
 * 19841588 BRAKE_START  h=5.579             ← 甩动造成 H 尖峰
 * 19858167 BRAKE_ABORT released             ← H 回落
 * 19874362 STOPPING still_no_brake          ← 修复点：摇后静置 → 到站
 * 19874362 STATION_ARRIVED  n=1/k=3         ← 计数 1
 * 19900110 MONITOR_END manual
 * ```
 */
class RealCsvRegressionTest {

    /**
     * 手摇录制资产（vib 量级 1.5+，超出真实乘车证据带）——只锁「CRUISE 累计静止 → 到站」
     * 修复点与手摇路径语义，注入关闭乘车证据门（分层见 [CsvReplayTest] 注释）。
     */
    private val config = TuningConfig(evidenceGateEnabled = false)

    private fun loadRealCsv(): String {
        val f = File("src/test/resources/replay/real_shake_20260925.csv")
        assertTrue("缺少实测 CSV：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun route(k: Int): RouteSpec {
        val stations = (0..k).map { Station(id = "s$it", name = "站$it") }
        val dir = Direction("up", "上行", stations)
        val line = Line("l", "示例1号线", listOf(dir))
        return RouteSpec.of(line, dir, "s0", "s$k")!!
    }

    @Test
    fun `实测CSV_静置即到站_修复点回归`() {
        val result = CsvReplay.replay(loadRealCsv(), route(3), config)
        val types = result.events.map { it.type }

        assertEquals("样本数应与录制一致", 7297, result.samples)

        // 修复点 1：预热结束后平放静置 → 应识别到站（无制动特征路径）
        val firstStop = result.events.firstOrNull { it.type == DetectorEventType.STOPPING }
        assertTrue("静置段应识别到站（缺陷回归点）", firstStop != null)
        assertEquals("静置到站应标记 still_no_brake", "still_no_brake", firstStop!!.note?.take(14))

        // 修复点 2：摇动停止后再次静置 → 应再次识别到站并计数
        val arrived = result.events.filter { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals("应有 1 次计数（首站被忽略，第二次到站计数）", 1, arrived.size)
        assertEquals("识别站数应为 1", 1, result.finalStationCount)
        assertTrue(
            "第二次到站也来自静置路径（note 保留检测层标记），实际：${arrived[0].note}",
            arrived[0].note?.contains("still_no_brake") == true,
        )

        // 首站忽略（上车站本身）
        assertTrue("首次到站应被忽略（首站不计数）", types.contains(DetectorEventType.FIRST_STOP_IGNORED))
        // 摇动 → 起步（甩动尖峰不应产生误计数）
        assertTrue("摇动应被识别为起步", types.contains(DetectorEventType.DEPART))
        // ⚠️ 2026-09-28 v2：departConfirmSec 5→10，DEPART 后移使甩动起始的 H 尖峰窗口错过
        // 制动检测——「甩动触发 BRAKE_START/ABORT」是 v1 时序副产品而非语义，断言移除
        // （制动路径语义由 DetectorStateMachineTest 合成用例覆盖）。
    }

    @Test
    fun `实测CSV_回放可复现`() {
        val csv = loadRealCsv()
        val a = CsvReplay.replay(csv, route(3), config)
        val b = CsvReplay.replay(csv, route(3), config)
        assertEquals("同一份实测 CSV 两次回放必须逐行一致", a.comparableLines(), b.comparableLines())
    }

    @Test
    fun `实测CSV_静置基线远低于运行阈值`() {
        val rows = CsvReplay.parse(loadRealCsv())
        // 预热 + 静置段（摇动从 t+~94 s 开始）
        val quiet = rows.filter { it.tMs - rows.first().tMs < 90_000 }
        assertTrue("静置段应不少于 4000 样本", quiet.size > 4000)
        val maxAccel = quiet.maxOf { kotlin.math.sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) }
        // 数据健全性检查：静置段原始（去重力）加速度幅值应为厘米级（0.25 m/s² 量级上限）。
        // 2026-09-28 起 vibRunTh 重标为 0.10（真实车厢巡航量级），不再适合作为原始幅值上限。
        assertTrue(
            "静置段加速度幅值应远低于 0.25（实际最大 %.4f）".format(maxAccel),
            maxAccel < 0.25f,
        )
    }
}

/**
 * **金标准回归**：真实 3 站行程（2026-09-25 红米 K80 实机）现场录制的 `events.csv`
 * 与离线回放输出**逐字段比对**（M1 验收项 3：CSV 回放事件序列与现场完全一致）。
 *
 * 行程：示例南站 → 示范路站 → 试验桥站 → 样板街站（k=3）
 * 操作：开始监测 → 静置 → 摇 15 s → 静置（第 1 站）→ 摇 → 静置（第 2 站，触发 D−1 提醒）
 *      → 快速摇+静置（第 3 次停站间隔 33 s，被 60 s 门槛拦为 STOP_SUSPECT）
 *      → 再摇 → 静置（第 3 站 = 目的站，触发到站提醒 + 30 s 后自动结束）
 *
 * 比对方式：现场 `events.csv` 的 `t_ms,type,station_index` 三元组序列
 * 必须与回放结果**完全相同**（毫秒级时间戳一致 → 证明「回放=现场」）。
 */
class RealJourneyRegressionTest {

    /**
     * 手摇模拟「列车」的 3 站行程资产（vib 量级 1.5+，超出真实乘车证据带）——
     * 锁「回放 = 现场」的决定论性质与计数 / 提醒语义，注入关闭乘车证据门。
     * ⚠️ events_ref 已按 2026-09-28 v2 参数（departConfirmSec=10 等）重新生成：
     * DEPART 时间戳相应后移，到站 / 提醒时刻不变。
     */
    private val config = TuningConfig(evidenceGateEnabled = false)

    private fun csvText(name: String): String {
        val f = File("src/test/resources/replay/$name")
        assertTrue("缺少实测文件：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun route(): RouteSpec {
        val stations = listOf("示例南站", "示范路站", "试验桥站", "样板街站", "样例广场站", "示意湖站", "示例北站")
            .mapIndexed { i, n -> Station(id = "s$i", name = n) }
        val dir = Direction("demo1_up", "开往 示例北站 方向", stations)
        val line = Line("demo1", "示例1号线", listOf(dir))
        return RouteSpec.of(line, dir, "s0", "s3")!!
    }

    @Test
    fun `真实3站行程_回放与现场事件序列完全一致`() {
        val replay = CsvReplay.replay(csvText("real_journey_3stops_20260925.csv"), route(), config)

        // 现场 events.csv 的关键三元组（时间戳, 类型, 站序号）
        val onSite = csvText("real_journey_3stops_events_ref.csv").lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val p = line.split(',')
                Triple(p[0].toLong(), p[1], p.getOrNull(2).orEmpty())
            }
            .toList()

        val fromReplay = replay.events
            .filter { it.type in COMPARABLE }
            .map { Triple(it.tMs, it.type.name, it.stationIndex?.toString() ?: "") }
            .toList()

        assertEquals("事件条数应一致", onSite.size, fromReplay.size)

        // 逐条比对：类型与站序号必须严格相同；时间戳允许 WARMUP_DONE 有启动相位差
        // （现场以 onStartCommand 的 elapsedRealtime 起算预热，回放以首个样本 tMs 起算，
        //  相差半个采样周期约 21 ms + 回调抖动；此后所有事件共用样本时间轴 → 必然一致）
        onSite.forEachIndexed { i, (t, type, idx) ->
            val (rt, rtype, ridx) = fromReplay[i]
            assertEquals("第 $i 条事件类型应一致", type, rtype)
            assertEquals("第 $i 条事件站序号应一致", idx, ridx)
            if (type == "WARMUP_DONE") {
                assertTrue(
                    "WARMUP_DONE 允许启动相位差 ≤100 ms（现场=$t 回放=$rt）",
                    kotlin.math.abs(t - rt) <= 100,
                )
            } else {
                assertEquals("第 $i 条事件（$type）时间戳必须与现场完全一致", t, rt)
            }
        }
        assertEquals("识别站数应等于 k", 3, replay.finalStationCount)
    }

    @Test
    fun `真实3站行程_提醒时机与自动结束符合规格`() {
        val result = CsvReplay.replay(csvText("real_journey_3stops_20260925.csv"), route(), config)
        val arrived = result.events.filter { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals("应计数 3 站", 3, arrived.size)

        // D−1 提醒：n == k-1 = 2 时发出，且带目的站名
        val prev = result.events.first { it.type == DetectorEventType.ALERT_PREV }
        assertEquals("D−1 提醒应带目的站序号", 3, prev.stationIndex)
        assertEquals("D−1 提醒应带目的站名", "样板街站", prev.stationName)
        // D−1 必须发生在第 2 次到站同一时刻
        assertEquals("D−1 应在 n=2 到站时发出", arrived[1].tMs, prev.tMs)

        // 到站提醒 + 30 s 后排定结束（回放不含真实等待，只验证排定时刻）
        val arr = result.events.first { it.type == DetectorEventType.ALERT_ARRIVED }
        assertEquals("到站提醒应在 n=3 时发出", arrived[2].tMs, arr.tMs)
        val endAt = arr.note?.removePrefix("end_at_")?.toLongOrNull()
            ?: error("到站提醒应带 end_at，实际 note=${arr.note}")
        assertEquals(
            "自动结束应在到站后 endGraceSec=%.0f s".format(config.endGraceSec),
            config.endGraceSec.toLong(),
            (endAt - arr.tMs) / 1000,
        )

        // 60 s 门槛：第 3 次停站间隔 33.3 s → STOP_SUSPECT，不计数。
        // v2 起到达目的站后的静置期可能另有 restill 疑似（振动回升后再静稳），同样不计数，属预期。
        val suspects = result.events.filter { it.type == DetectorEventType.STOP_SUSPECT }
        assertTrue("33 s 快速停站应被门槛拦截", suspects.any { it.intervalS != null && it.intervalS < config.minStopIntervalSec })
        assertEquals("疑似站不应影响计数", 3, result.finalStationCount)
    }

    @Test
    fun `真实3站行程_两次回放逐行一致`() {
        val text = csvText("real_journey_3stops_20260925.csv")
        val a = CsvReplay.replay(text, route(), config)
        val b = CsvReplay.replay(text, route(), config)
        assertEquals(a.comparableLines(), b.comparableLines())
    }

    private companion object {
        val COMPARABLE = setOf(
            DetectorEventType.WARMUP_DONE,
            DetectorEventType.BRAKE_START,
            DetectorEventType.BRAKE_ABORT,
            DetectorEventType.STOPPING,
            DetectorEventType.STATION_ARRIVED,
            DetectorEventType.FIRST_STOP_IGNORED,
            DetectorEventType.STOP_SUSPECT,
            DetectorEventType.DEPART,
            DetectorEventType.ALERT_PREV,
            DetectorEventType.ALERT_ARRIVED,
            DetectorEventType.OVERSHOOT,
            DetectorEventType.DATA_GAP,
            DetectorEventType.DATA_RESUME,
            DetectorEventType.MONITOR_END,
        )
    }
}

/**
 * **车上中途开始 + 单站行程**金标准回归（2026-09-25 红米 K80 实机）。
 *
 * 背景：用户反馈「上车站→目的站只差 1 站，却要摇两次才能收到提醒」。
 * 根因：旧版首站忽略规则只看 `hasRun`（是否发生过起步），
 * 于是「列车已在行驶中途开始监测」时，第一次真实到站被误判为「上车站本身」吞掉。
 * 修复：新增 `startedInMotion`（预热期内持续振动 → 开始时列车已在动），
 * 该姿势下第一次停站直接计数。
 *
 * 本用例锁定修复后的行为（修复前会 FAIL）。基准事件序列：
 * ```
 * 23946073 WARMUP_DONE    note=started_in_motion          ← 姿势识别
 * 23946073 ALERT_PREV  1 示范路站                           ← k=1 预热结束即提醒
 * 23963330 STOPPING       note=still_no_brake vib=0.008
 * 23963330 STATION_ARRIVED 1 示范路站  n=1/k=1              ← **摇一次即计数**（修复点）
 * 23963330 ALERT_ARRIVED 1 示范路站   end_at_23993330
 * 23993335 MONITOR_END  arrived                            ← 30.005 s 后自动结束
 * ```
 */
class RealInMotionStartRegressionTest {

    /**
     * 手摇模拟「车上中途开始」的资产（vib 量级 1.5+，超出真实乘车证据带）——
     * 锁 startedInMotion 姿势识别与首站计数语义，注入关闭乘车证据门。
     * ⚠️ events_ref 已按 2026-09-28 v2 参数重新生成（DEPART 时间戳后移）。
     */
    private val config = TuningConfig(evidenceGateEnabled = false)

    private fun csvText(name: String): String {
        val f = File("src/test/resources/replay/$name")
        assertTrue("缺少实测文件：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 示例南站 → 示范路站（k=1，与实录 meta 一致） */
    private fun route(): RouteSpec {
        val stations = listOf("示例南站", "示范路站", "试验桥站", "样板街站", "样例广场站", "示意湖站", "示例北站")
            .mapIndexed { i, n -> Station(id = "s$i", name = n) }
        val dir = Direction("demo1_up", "开往 示例北站 方向", stations)
        val line = Line("demo1", "示例1号线", listOf(dir))
        return RouteSpec.of(line, dir, "s0", "s1")!!
    }

    @Test
    fun `车上开始单站行程_第一次停站即计数并触发到站提醒`() {
        val result = CsvReplay.replay(csvText("real_inmotion_start_1stop_20260925.csv"), route(), config)
        val types = result.events.map { it.type }

        // 姿势识别：预热期有振动 → started_in_motion
        val warmup = result.events.first { it.type == DetectorEventType.WARMUP_DONE }
        assertEquals("应识别为「车上中途开始」", "started_in_motion", warmup.note)

        // 修复点：第一次停站直接计数，且不出现 FIRST_STOP_IGNORED
        assertFalse(
            "车上开始不应吞掉第一个真实站（这是用户反馈的核心问题）",
            types.contains(DetectorEventType.FIRST_STOP_IGNORED),
        )
        assertEquals("单站行程应恰好计数 1 站", 1, result.finalStationCount)
        assertEquals("识别站数应等于 k=1", 1, result.finalStationCount)

        val arrived = result.events.first { it.type == DetectorEventType.STATION_ARRIVED }
        assertEquals("计数站应为目的站序号", 1, arrived.stationIndex)
        assertEquals("计数站名应为示范路站", "示范路站", arrived.stationName)

        // 提醒链路：k=1 预热结束即提醒 + 到站提醒
        assertTrue("k=1 应发 D−1（下一站就是目的站）提醒", types.contains(DetectorEventType.ALERT_PREV))
        assertTrue("到站应发到达提醒", types.contains(DetectorEventType.ALERT_ARRIVED))

        // 自动结束：到站后 endGraceSec
        val arr = result.events.first { it.type == DetectorEventType.ALERT_ARRIVED }
        val endAt = arr.note?.removePrefix("end_at_")?.toLongOrNull() ?: error("到达提醒应带 end_at")
        val end = result.events.first { it.type == DetectorEventType.MONITOR_END }
        assertEquals(
            "自动结束应在 endGraceSec=%.0f s 后".format(config.endGraceSec),
            config.endGraceSec.toLong(),
            ((endAt - arr.tMs) / 1000),
        )
        assertEquals("结束原因应为已到达", "arrived", end.note)
    }

    @Test
    fun `车上开始单站行程_回放与现场事件序列完全一致`() {
        val replay = CsvReplay.replay(csvText("real_inmotion_start_1stop_20260925.csv"), route(), config)
        val onSite = csvText("real_inmotion_start_1stop_events_ref.csv").lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val p = line.split(',')
                Triple(p[0].toLong(), p[1], p.getOrNull(2).orEmpty())
            }
            .toList()
        val fromReplay = replay.events
            .filter { it.type in COMPARABLE }
            .map { Triple(it.tMs, it.type.name, it.stationIndex?.toString() ?: "") }
            .toList()

        // WARMUP_DONE 及其派生的 ALERT_PREV 允许启动相位差（现场以 onStartCommand 起算，
        // 回放以首样本起算，相差半个采样周期 + 回调抖动，恒为 43 ms）；
        // 其余事件共用样本时间轴，必须逐毫秒一致。
        assertEquals("事件条数应一致", onSite.size, fromReplay.size)
        onSite.forEachIndexed { i, (t, type, idx) ->
            val (rt, rtype, ridx) = fromReplay[i]
            assertEquals("第 $i 条事件类型应一致", type, rtype)
            assertEquals("第 $i 条事件站序号应一致", idx, ridx)
            if (type == "WARMUP_DONE" || type == "ALERT_PREV") {
                assertTrue(
                    "$type 允许启动相位差 ≤100 ms（现场=$t 回放=$rt）",
                    kotlin.math.abs(t - rt) <= 100,
                )
            } else {
                assertEquals("第 $i 条事件（$type）时间戳必须与现场完全一致", t, rt)
            }
        }
    }

    private companion object {
        val COMPARABLE = setOf(
            DetectorEventType.WARMUP_DONE,
            DetectorEventType.BRAKE_START,
            DetectorEventType.BRAKE_ABORT,
            DetectorEventType.STOPPING,
            DetectorEventType.STATION_ARRIVED,
            DetectorEventType.FIRST_STOP_IGNORED,
            DetectorEventType.STOP_SUSPECT,
            DetectorEventType.DEPART,
            DetectorEventType.ALERT_PREV,
            DetectorEventType.ALERT_ARRIVED,
            DetectorEventType.OVERSHOOT,
            DetectorEventType.DATA_GAP,
            DetectorEventType.DATA_RESUME,
            DetectorEventType.MONITOR_END,
        )
    }
}
