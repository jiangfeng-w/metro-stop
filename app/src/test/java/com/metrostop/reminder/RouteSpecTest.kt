package com.metrostop.reminder

import com.metrostop.reminder.core.model.Direction
import com.metrostop.reminder.core.model.Line
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.route.LineRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路线模型与站点 JSON 解析（总纲 5.4 计数语义） */
class RouteSpecTest {

    private val stations = listOf("A", "B", "C", "D", "E").mapIndexed { i, n ->
        Station(id = "s${i + 1}", name = "$n 站")
    }

    private val direction = Direction(id = "up", name = "开往 E 方向", stations = stations)
    private val line = Line(id = "l1", name = "1 号线", directions = listOf(direction))

    @Test
    fun `stopCount 由站序推算`() {
        val r = RouteSpec.of(line, direction, "s2", "s4")!!
        assertEquals(2, r.stopCount)
        assertEquals("B 站", r.boardingStation.name)
        assertEquals("D 站", r.destinationStation.name)
    }

    @Test
    fun `计数语义 n 递增时 当前站_下一站_剩余 正确`() {
        val r = RouteSpec.of(line, direction, "s2", "s5")!! // k = 3
        assertEquals(3, r.stopCount)

        assertEquals("B 站", r.currentStation(0).name)
        assertEquals("C 站", r.nextStation(0).name)
        assertEquals(3, r.remaining(0))

        assertEquals("C 站", r.currentStation(1).name)
        assertEquals("D 站", r.nextStation(1).name)
        assertEquals(2, r.remaining(1))

        // n == k：已到目的站，剩余 0，下一站钳制为目的站
        assertEquals("E 站", r.currentStation(3).name)
        assertEquals(0, r.remaining(3))
        assertEquals("E 站", r.nextStation(3).name)
    }

    @Test
    fun `目的站必须在上车站之后`() {
        assertNull(RouteSpec.of(line, direction, "s4", "s2"))
        assertNull(RouteSpec.of(line, direction, "s3", "s3"))
        assertNotNull(RouteSpec.of(line, direction, "s1", "s2"))
    }

    @Test
    fun `k_等于1 时剩余与提醒特例可用`() {
        val r = RouteSpec.of(line, direction, "s4", "s5")!!
        assertEquals(1, r.stopCount)
        assertEquals(1, r.remaining(0))
        assertEquals("E 站", r.nextStation(0).name)
    }

    @Test
    fun `解析示例JSON并按方向查询目的站候选`() {
        val json = """
        {
          "schemaVersion": 1,
          "city": "示例市",
          "lines": [
            { "id": "l1", "name": "1 号线",
              "directions": [
                { "id": "up", "name": "上行",
                  "stations": [
                    {"id":"s1","name":"A 站"},
                    {"id":"s2","name":"B 站"},
                    {"id":"s3","name":"C 站"}
                  ]}
              ]}
          ]
        }
        """.trimIndent()

        val repo = LineRepository.parse(json).getOrThrow()
        assertEquals("示例市", repo.city)
        val candidates = repo.destinationCandidates("l1", "up", "s1")
        assertEquals(listOf("B 站", "C 站"), candidates.map { it.name })

        assertTrue(repo.isValid("l1", "up", "s1", "s3"))
        assertFalse(repo.isValid("l1", "up", "s3", "s1"))
        assertFalse(repo.isValid("l1", "up", null, "s3"))
    }

    @Test
    fun `坏数据不会抛异常`() {
        val json = """{ "lines": [ { "id": "", "name": "空", "directions": [] } ] }"""
        val repo = LineRepository.parse(json).getOrThrow()
        assertTrue(repo.lines.isEmpty())
    }
}
