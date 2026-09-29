package com.metrostop.reminder

import com.metrostop.reminder.core.cell.CellZoneLine
import com.metrostop.reminder.core.cell.CellZoneMap
import com.metrostop.reminder.core.cell.CellZoneStation
import com.metrostop.reminder.core.cell.CellZoneTracker
import com.metrostop.reminder.core.cell.ZoneTransition
import com.metrostop.reminder.core.model.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蜂窝站区跟踪器纯函数回归（cell-zone-detector-v4）。
 *
 * 场景取自 2026-09-29 早通勤实测的小区序列形态：
 * - 进区需连续 ≥2 拍确认（邻区列表瞬闪不触发）；
 * - 不命中任何站区的小区 → 立即退区（信号停车小区如 716 不构成站区）；
 * - 同一 pci:ci 覆盖相邻多站 → 位置区间（4 号线 701 区覆盖市二医院+太升南路）；
 * - 主服务优先，主服务不中再试邻区。
 */
class CellZoneTrackerTest {

    private val config = TuningConfig.Default

    /** 6 号线 望丛祠方向前 3 站 + 4 号线共享区形态的混合测试映射 */
    private val lineMap = CellZoneLine(
        lineId = "cd6",
        directionId = "cd6_to_wangcongzi",
        rideCount = 1,
        stations = listOf(
            CellZoneStation("cd6_s37", listOf("721:38656778242")),
            CellZoneStation("cd6_s36", listOf("799:38656573441", "905:38656774146")),
            CellZoneStation("cd6_s35", listOf("860:38656569345")),
            CellZoneStation("cd6_s34", listOf("887:38682628098")),
        ),
    )

    @Test
    fun `进区需连续两拍确认`() {
        val t = CellZoneTracker(lineMap, config)
        // 单拍命中：只形成候选，不确认
        assertTrue(t.onCell(1000, "721:38656778242", emptyList()).isEmpty())
        assertNull(t.confirmedRange)
        // 第二拍同区：确认进区
        val tr = t.onCell(2000, "721:38656778242", emptyList())
        assertEquals(listOf<ZoneTransition>(ZoneTransition.Entered(2000, 1..1)), tr)
        assertEquals(1..1, t.confirmedRange)
    }

    @Test
    fun `候选切换重置确认计数`() {
        val t = CellZoneTracker(lineMap, config)
        t.onCell(1000, "721:38656778242", emptyList())
        // 中间插一拍别的小区（未命中）→ 候选清零
        t.onCell(1500, "999:123", emptyList())
        assertTrue(t.onCell(2000, "721:38656778242", emptyList()).isEmpty())
        // 再一拍才确认
        val tr = t.onCell(3000, "721:38656778242", emptyList())
        assertEquals(1, tr.size)
        assertTrue(tr[0] is ZoneTransition.Entered)
    }

    @Test
    fun `区外小区立即退区`() {
        val t = CellZoneTracker(lineMap, config)
        t.onCell(1000, "721:38656778242", emptyList())
        t.onCell(2000, "721:38656778242", emptyList())
        assertEquals(1..1, t.confirmedRange)
        // 区间信号停车小区（如 716）不构成站区 → 立即退区
        val tr = t.onCell(3000, "716:38656573481", emptyList())
        assertEquals(listOf<ZoneTransition>(ZoneTransition.Exited(3000)), tr)
        assertNull(t.confirmedRange)
    }

    @Test
    fun `主服务不中时邻区兜底`() {
        val t = CellZoneTracker(lineMap, config)
        t.onCell(1000, "888:111", listOf("999:222", "860:38656569345"))
        t.onCell(2000, "888:111", listOf("860:38656569345"))
        assertEquals(3..3, t.confirmedRange)
    }

    @Test
    fun `同一小区覆盖相邻多站时返回位置区间`() {
        val t = CellZoneTracker(lineMap, config)
        // 模拟 4 号线共享小区：一个 cell 挂在相邻两个站上
        val shared = CellZoneLine(
            lineId = "cd4",
            directionId = "cd4_to_wansheng",
            stations = listOf(
                CellZoneStation("cd4_s21", listOf("701:38658715650")),
                CellZoneStation("cd4_s20", listOf("701:38658715650")),
            ),
        )
        val ts = CellZoneTracker(shared, config)
        ts.onCell(1000, "701:38658715650", emptyList())
        ts.onCell(2000, "701:38658715650", emptyList())
        assertEquals(1..2, ts.confirmedRange)
    }

    @Test
    fun `无服务与空小区不触发`() {
        val t = CellZoneTracker(lineMap, config)
        assertTrue(t.onCell(1000, null, emptyList()).isEmpty())
        assertTrue(t.onCell(2000, "", emptyList()).isEmpty())
        assertNull(t.confirmedRange)
    }

    @Test
    fun `映射缺失时跟踪器不激活`() {
        val t = CellZoneTracker(null, config)
        assertTrue(!t.active)
        assertTrue(t.onCell(1000, "721:38656778242", emptyList()).isEmpty())
        assertNull(t.confirmedRange)
    }

    @Test
    fun `站序校验失败的方向不返回映射`() {
        val route = com.metrostop.reminder.core.model.RouteSpec(
            lineId = "cd6",
            lineName = "6号线",
            directionId = "cd6_to_wangcongzi",
            directionName = "开往 望丛祠 方向",
            stations = listOf(
                com.metrostop.reminder.core.model.Station("cd6_s38", "观东"),
                com.metrostop.reminder.core.model.Station("cd6_s37", "陆肖"),
            ),
            boardingIndex = 0,
            destinationIndex = 1,
        )
        // 期望首站 cd6_s37，映射给的是 cd6_s99 → 站序不符 → forRoute = null（降级 v3）
        val bad = CellZoneMap.parse(
            """{"lines":[{"lineId":"cd6","directionId":"cd6_to_wangcongzi",
                "stations":[{"stationId":"cd6_s99","cells":["721:38656778242"]}]}]}""",
        ).getOrThrow()
        assertNull(bad.forRoute(route))
        // 站序一致 → 返回映射
        val good = CellZoneMap.parse(
            """{"lines":[{"lineId":"cd6","directionId":"cd6_to_wangcongzi",
                "stations":[{"stationId":"cd6_s37","cells":["721:38656778242"]}]}]}""",
        ).getOrThrow()
        assertTrue(good.forRoute(route) != null)
    }
}
