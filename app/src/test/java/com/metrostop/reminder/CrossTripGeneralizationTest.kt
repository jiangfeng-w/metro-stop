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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **跨趟泛化回放（节中批次 B1 后续，硬性规则 2）**。
 *
 * 数据：2026-09-30 晚通勤 cd6 段（玉双路 → 观东 12 站，v4 主通道第 2 趟实测，现场 12/12 计数对
 * 但 3 次纠错不达标）。映射：cd6_to_lanjiagou——2026-10-07 起资产为**两趟并集**（rideCount=2），
 * 本用例同时锁定「并集后（现资产）」与「剥掉第 2 趟增量（= 原 rideCount=1 映射）」两种行为。
 *
 * MARK 真值（12 个「列车进站停稳」，t0=442588654 ms，会话相对秒）：
 * ```
 * 牛王庙+275.3 顺江路+432.8 三官堂+517.5 东光+641.7 琉璃场+785.5 琉三路+899.1
 * 金石路+1049.7 金融城东+1193.7 中和+1395.4 张家寺+1543.7 陆肖+1680.9 观东+1784.5
 * ```
 *
 * 离线审计（仓库外 `analysis/cross_trip_audit.py`，2026-10-07）结论：
 * - 两趟学习窗均无发车段污染——B1' 存档的「牛王庙 MARK 晚标 30s 污染」被证伪：两趟学的都是
 *   各自当天的站台小区（853/799 均为本站 ≥68s 站台段驻留），跨趟差异是**真实的扇区漂移**；
 * - 第 2 趟真值相对第 1 趟映射失配 5 站（牛王庙/顺江路/三官堂/琉璃场/陆肖，as-is 交集 7/12）；
 * - 两趟并集登记 **0 处串站**（全部 ≥10s 主服务驻留段落点在本站）。
 *
 * 已知基线（两种映射共有，非映射问题）：会话起点在玉双路换乘走动中，巡航确认假置位
 * `hasRun`（`cruiseRunConfirmSec` 融合层已知 bug）→ 上车站台停稳被计成牛王庙（+121.5s）、
 * 发车前再次静稳被计成顺江路（+187.1s）——现场「开头错位」的忠实复现；节后修融合层时
 * 同步更新本用例的第 1/2 条计数断言。
 */
class CrossTripGeneralizationTest {

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

    /** 玉双路 → 观东（6 号线 兰家沟方向，12 站） */
    private fun route(): RouteSpec = RouteSpec.of(
        repo.line("cd6") ?: throw AssertionError("找不到线路 cd6"),
        repo.direction("cd6", "cd6_to_lanjiagou") ?: throw AssertionError("找不到方向"),
        "cd6_s26", // 玉双路（上车站，映射不含）
        "cd6_s38", // 观东
    ) ?: throw AssertionError("路线构造失败")

    /** 第 2 趟（09-30 晚）站台真值增量：rideCount=2 并集相对 rideCount=1 的全部差异（审计 B 段）。 */
    private val trip2Cells = mapOf(
        "cd6_s27" to "799:38682599426",   // 牛王庙（= 早方向同小区）
        "cd6_s28" to "761:38682603522",   // 顺江路（同 gNB 异扇区）
        "cd6_s29" to "732:38682640425",   // 三官堂（同 gNB 异扇区）
        "cd6_s31" to "879:38682615810",   // 琉璃场（= 早方向同小区）
        "cd6_s32" to "708:38682619905",   // 琉三路（= 早方向同小区）
        "cd6_s37" to "807:38656577538",   // 陆肖（同 gNB 异扇区）
    )

    /** 现资产（并集，rideCount=2） */
    private fun unionLine(): CellZoneLine {
        val line = zoneMap.forRoute(route()) ?: throw AssertionError("路线取映射失败")
        // 资产回归守卫：并集落地后不得回退（防 gen 脚本/手工编辑把第 2 趟增量洗掉）
        for (st in line.stations) {
            val c = trip2Cells[st.stationId]
            if (c != null) assertTrue("资产缺少第 2 趟增量 $st", c in st.cells)
        }
        return line
    }

    /** 剥掉第 2 趟增量 = 原 rideCount=1 映射（09-29 晚单趟学习） */
    private fun rideCount1Line(): CellZoneLine {
        val line = unionLine()
        return line.copy(
            rideCount = 1,
            stations = line.stations.map { st ->
                val c = trip2Cells[st.stationId]
                if (c == null) st else st.copy(cells = st.cells - c)
            },
        )
    }

    private fun replay(zoneLine: CellZoneLine): ReplayResult =
        CsvReplay.replay(
            text("real_commute_cd6_12stops_evening_20260930.csv"),
            route(),
            v4Config,
            cellCsv = text("real_commute_cd6_12stops_evening_cells_20260930.csv"),
            zoneLine = zoneLine,
        )

    private fun arrivals(r: ReplayResult) =
        r.events.filter { it.type == DetectorEventType.STATION_ARRIVED }

    private fun zoneConfirms(r: ReplayResult) =
        r.events.filter { it.type == DetectorEventType.ZONE_CONFIRMED }

    private val allStations = listOf(
        "牛王庙", "顺江路", "三官堂", "东光", "琉璃场", "琉三路",
        "金石路", "金融城东", "中和", "张家寺", "陆肖", "观东",
    )

    /** 第 2 趟 MARK 真值（t_ms） */
    private val marks = mapOf(
        "牛王庙" to 442863961L, "顺江路" to 443021446L, "三官堂" to 443106124L,
        "东光" to 443230351L, "琉璃场" to 443374119L, "琉三路" to 443487784L,
        "金石路" to 443638390L, "金融城东" to 443782330L, "中和" to 443984064L,
        "张家寺" to 444132326L, "陆肖" to 444269594L, "观东" to 444373201L,
    )

    private val t0 = 442588654L

    @Test
    fun `并集映射_12站区全确认_零重同步_真值对齐`() {
        val r = replay(unionLine())

        // 1. 计数 12/12，12 条到站事件零 resync（每个站都有独立的到站时刻）
        assertEquals(12, r.finalStationCount)
        val arr = arrivals(r)
        assertEquals("到站序列: ${arr.map { it.stationName }}", allStations, arr.map { it.stationName })
        assertTrue(
            "不应有 resync（站区身份逐站命中）: ${arr.filter { "resync" in (it.note ?: "") }}",
            arr.none { "resync" in (it.note ?: "") },
        )

        // 2. 12/12 站区确认（泛化命中 = 每一站的站区都被进区确认过）
        assertEquals(allStations.toSet(), zoneConfirms(r).map { it.stationName }.toSet())

        // 3. 除上车段 2 个 phantom 外，10 个真到站时刻全部落在 MARK ±45 s
        //    （zone_still 侦察天然提前于停稳、张家寺小区在停稳后 +8s 才服务，容差覆盖实测极值 ±40s）
        for (a in arr) {
            val mk = marks[a.stationName] ?: continue
            if (a.stationName == "牛王庙" || a.stationName == "顺江路") continue // 上车段 phantom（见类注释）
            val devSec = (a.tMs - mk) / 1000.0
            assertTrue(
                "${a.stationName} 到站偏差 ${devSec}s 超出 ±45s",
                Math.abs(devSec) <= 45.0,
            )
        }

        // 4. 三级提醒齐全：D−1（陆肖区→观东）+ 即将到站 + 已到站（rideCount=1 时 D−1 丢失）
        assertTrue(r.events.any { it.type == DetectorEventType.ALERT_PREV })
        assertTrue(r.events.any { it.type == DetectorEventType.ALERT_DEST_SOON })
        assertTrue(r.events.any { it.type == DetectorEventType.ALERT_ARRIVED })

        // 5. 数满自动结束
        assertTrue(r.events.any { it.type == DetectorEventType.MONITOR_END })
    }

    @Test
    fun `单趟映射基线_站区仅7之12命中_resync自愈_D减1丢失`() {
        val r = replay(rideCount1Line())

        // 1. 计数靠「漏检重同步」自愈到 12/12，但只有 10 条到站事件（2 站被 resync 跳数）
        assertEquals(12, r.finalStationCount)
        val arr = arrivals(r)
        assertEquals(10, arr.size)
        val resynced = arr.filter { "resync" in (it.note ?: "") }
        assertEquals(listOf("琉三路", "观东"), resynced.map { it.stationName })

        // 2. 泛化命中 7/12：牛王庙/顺江路/三官堂/琉璃场/陆肖 五站站区从未确认
        //    （与离线小区流交集审计 7/12 一致——端到端证实）
        assertEquals(
            setOf("东光", "琉三路", "金石路", "金融城东", "中和", "张家寺", "观东"),
            zoneConfirms(r).map { it.stationName }.toSet(),
        )

        // 3. 上车段 phantom 双计数（融合层已知 bug 的忠实复现，非映射问题）
        assertTrue(arr[0].stationName == "牛王庙" && arr[0].tMs - t0 < 150_000)
        assertTrue(arr[1].stationName == "顺江路" && arr[1].tMs - t0 < 210_000)

        // 4. D−1 丢失（陆肖区未确认 → 观东的前站提醒发不出）；到站提醒仍在
        assertFalse(
            "rideCount=1 映射下 D−1 应丢失（陆肖区失配）",
            r.events.any { it.type == DetectorEventType.ALERT_PREV },
        )
        assertTrue(r.events.any { it.type == DetectorEventType.ALERT_DEST_SOON })
        assertTrue(r.events.any { it.type == DetectorEventType.ALERT_ARRIVED })
    }
}
