package com.metrostop.reminder

import com.metrostop.reminder.core.route.LineRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资产线路数据回归（`app/src/main/assets/subway_lines.json`）。
 *
 * 目的：真实线路数据是本 App 提醒正确性的**前提**（数错一站就会早/晚提醒），
 * 站点数量大（4 号线 30 + 6 号线 56），人工核对容易漏。本测试把
 * ① 站数 / 端点 / 关键站序，② 用户通勤路线的 **stopCount 验收数字**，
 * ③ id 唯一性与解析清洗一致性 —— 全部锁成断言，防数据手抖回归。
 *
 * 数据来源与三源交叉核对见 `docs/spec/active/real-line-data/需求.md` 第四节。
 * 生成脚本：`docs/spec/active/real-line-data/assets/gen_lines.py`。
 */
class SubwayDataAssetTest {

    private val json: String by lazy {
        // 单测工作目录 = app/（与既有 RealCsvRegressionTest 的 resources 读取方式一致）
        val f = File("src/main/assets/subway_lines.json")
        assertTrue("找不到资产数据：${f.absolutePath}", f.isFile)
        f.readText()
    }

    private val repo: LineRepository by lazy {
        LineRepository.parse(json).getOrElse { throw AssertionError("资产 JSON 解析失败：$it") }
    }

    private fun direction(lineId: String, directionId: String) =
        repo.direction(lineId, directionId)
            ?: throw AssertionError("找不到 $lineId / $directionId")

    private fun indexOf(lineId: String, directionId: String, stationName: String): Int {
        val i = direction(lineId, directionId).stations.indexOfFirst { it.name == stationName }
        assertTrue("$lineId/$directionId 中找不到站「$stationName」", i >= 0)
        return i
    }

    // ------------------------------------------------------------ 基本结构

    @Test
    fun `城市为成都且仅有本期 4 与 6 号线`() {
        assertEquals("成都", repo.city)
        assertEquals(listOf("cd4", "cd6"), repo.lines.map { it.id })
    }

    @Test
    fun `站数与端点正确`() {
        // 4 号线：西河 ↔ 万盛，30 站（与官网「共计30座车站」一致）
        val cd4f = direction("cd4", "cd4_to_xihe")
        assertEquals(30, cd4f.stations.size)
        assertEquals("万盛", cd4f.stations.first().name)
        assertEquals("西河", cd4f.stations.last().name)

        val cd4r = direction("cd4", "cd4_to_wansheng")
        assertEquals("西河", cd4r.stations.first().name)
        assertEquals("万盛", cd4r.stations.last().name)

        // 6 号线：望丛祠 ↔ 兰家沟，56 站（与官网「共计56座车站」一致）
        val cd6f = direction("cd6", "cd6_to_lanjiagou")
        assertEquals(56, cd6f.stations.size)
        assertEquals("望丛祠", cd6f.stations.first().name)
        assertEquals("兰家沟", cd6f.stations.last().name)

        val cd6r = direction("cd6", "cd6_to_wangcongzi")
        assertEquals("兰家沟", cd6r.stations.first().name)
        assertEquals("望丛祠", cd6r.stations.last().name)
    }

    @Test
    fun `反向站序为正向倒置`() {
        val f = direction("cd6", "cd6_to_lanjiagou").stations
        val r = direction("cd6", "cd6_to_wangcongzi").stations
        assertEquals(f.map { it.name }.reversed(), r.map { it.name })
        assertEquals(f.map { it.id }.reversed(), r.map { it.id })
    }

    @Test
    fun `每条线路方向 id 唯一且两方向`() {
        repo.lines.forEach { line ->
            assertEquals(2, line.directions.size)
            assertEquals(2, line.directions.map { it.id }.distinct().size)
            line.directions.forEach { d ->
                assertTrue(d.id.isNotBlank())
                assertTrue(d.stations.isNotEmpty())
            }
        }
    }

    @Test
    fun `站 id 与站名在线内无重复 且无空值`() {
        repo.lines.forEach { line ->
            line.directions.forEach { d ->
                val ids = d.stations.map { it.id }
                val names = d.stations.map { it.name }
                assertEquals("${d.id} 站 id 有重复", ids.size, ids.distinct().size)
                assertEquals("${d.id} 站名有重复", names.size, names.distinct().size)
                assertTrue("${d.id} 存在空 id", ids.none { it.isBlank() })
                assertTrue("${d.id} 存在空站名", names.none { it.isBlank() })
                // 站 id 命名约定：cd<ref>_sNN，编号即正向站序
                val prefix = line.id + "_s"
                assertTrue(
                    "${d.id} 站 id 不符合 ${prefix}NN 约定：$ids",
                    ids.all { it.startsWith(prefix) && it.removePrefix(prefix).toIntOrNull() != null },
                )
            }
        }
    }

    // ------------------------------------------------------------ 通勤路线验收数字

    @Test
    fun `上班第一乘 观东到玉双路 为 12 站 目的站前一站是牛王庙`() {
        // 6 号线开往望丛祠方向：兰家沟 → … → 观东 → … → 玉双路 → … → 望丛祠
        val route = repo.buildRoute("cd6", "cd6_to_wangcongzi", "cd6_s38", "cd6_s26")
            ?: throw AssertionError("观东 → 玉双路 无法构成合法路线")
        assertEquals(12, route.stopCount)
        assertEquals("观东", route.boardingStation.name)
        assertEquals("玉双路", route.destinationStation.name)
        // D−1 提醒站 = 目的站前一站
        assertEquals("牛王庙", route.stationNameAt(route.destinationIndex - 1))
        // n = 11 时当前站是牛王庙，下一站是玉双路
        assertEquals("牛王庙", route.currentStation(11).name)
        assertEquals("玉双路", route.nextStation(11).name)
        assertEquals(1, route.remaining(11))
    }

    @Test
    fun `下班第一乘 玉双路到观东 为 12 站`() {
        val route = repo.buildRoute("cd6", "cd6_to_lanjiagou", "cd6_s26", "cd6_s38")
            ?: throw AssertionError("玉双路 → 观东 无法构成合法路线")
        assertEquals(12, route.stopCount)
        assertEquals("玉双路", route.boardingStation.name)
        assertEquals("观东", route.destinationStation.name)
    }

    @Test
    fun `上班第二乘 玉双路到太升南路 为 2 站 目的站前一站是市二医院`() {
        // 4 号线开往万盛方向：西河 → … → 玉双路 → 市二医院 → 太升南路 → … → 万盛
        val route = repo.buildRoute("cd4", "cd4_to_wansheng", "cd4_s22", "cd4_s20")
            ?: throw AssertionError("玉双路 → 太升南路 无法构成合法路线")
        assertEquals(2, route.stopCount)
        assertEquals("玉双路", route.boardingStation.name)
        assertEquals("太升南路", route.destinationStation.name)
        assertEquals("市二医院", route.stationNameAt(route.destinationIndex - 1))
    }

    @Test
    fun `关键站序与官方一致`() {
        // 6 号线（望丛祠 → 兰家沟 正向 = 数据 order）：观东 #38、玉双路 #26、牛王庙 #27、建设北路 #24
        assertEquals(37, indexOf("cd6", "cd6_to_lanjiagou", "观东")) // 0 基 = 第 38 站
        assertEquals(25, indexOf("cd6", "cd6_to_lanjiagou", "玉双路"))
        assertEquals(26, indexOf("cd6", "cd6_to_lanjiagou", "牛王庙"))
        assertEquals(23, indexOf("cd6", "cd6_to_lanjiagou", "建设北路"))
        assertTrue(
            "6 号线应使用官方现名「建设北路」",
            direction("cd6", "cd6_to_lanjiagou").stations.none { it.name == "电子科大建设北路" },
        )
        // 4 号线（万盛 → 西河 正向）：玉双路 #22、市二医院 #21、太升南路 #20
        assertEquals(21, indexOf("cd4", "cd4_to_xihe", "玉双路"))
        assertEquals(20, indexOf("cd4", "cd4_to_xihe", "市二医院"))
        assertEquals(19, indexOf("cd4", "cd4_to_xihe", "太升南路"))
    }

    @Test
    fun `换乘站玉双路在 4 与 6 号线均存在`() {
        assertTrue(repo.direction("cd6", "cd6_to_lanjiagou")!!.stations.any { it.name == "玉双路" })
        assertTrue(repo.direction("cd4", "cd4_to_xihe")!!.stations.any { it.name == "玉双路" })
    }

    @Test
    fun `玉双路至太升南路的候选目的站顺序正确`() {
        // 开往万盛方向、玉双路上车：下一站市二医院，再下一站太升南路
        val candidates = repo.destinationCandidates("cd4", "cd4_to_wansheng", "cd4_s22")
        assertEquals("市二医院", candidates.first().name)
        assertEquals("太升南路", candidates[1].name)
    }

    // ------------------------------------------------------------ 数据与清洗一致性

    @Test
    fun `所有线路均为两个方向且上车站之后必有目的站`() {
        repo.lines.forEach { line ->
            line.directions.forEach { d ->
                // 每条方向至少 2 站（否则无法构成任何路线）
                assertTrue("${d.id} 站数不足", d.stations.size >= 2)
                // 首站到末站应构成合法路线
                val r = repo.buildRoute(line.id, d.id, d.stations.first().id, d.stations.last().id)
                assertNotNull("${d.id} 首→末站无法构成路线", r)
                assertEquals(d.stations.size - 1, r!!.stopCount)
            }
        }
    }

    @Test
    fun `解析清洗不丢弃任何站`() {
        // LineRepository.parse 会按 id 去重并丢弃空项；真实数据不应触发这些清洗。
        // 站 id 形如 "cd4_s01" / "cd6_s56"，在正向 + 反向两个方向各出现一次。
        val rawStationIds = Regex("\"id\":\\s*\"cd[46]_s\\d+\"").findAll(json).count()
        val repoCount = repo.lines.sumOf { l -> l.directions.sumOf { it.stations.size } }
        assertEquals("清洗前后站条目数不一致（可能有重复 id 被静默丢弃）", rawStationIds, repoCount)
        // 4 号线 30*2 + 6 号线 56*2 = 172
        assertEquals(172, repoCount)
    }
}
