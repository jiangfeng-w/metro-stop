package com.metrostop.reminder

import com.metrostop.reminder.core.lab.LabScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通勤精简模式的场景可见性回归（2026-09-30，lab 旁路 UI，防通勤途中误点） */
class LabScenariosTest {

    @Test
    fun `精简模式只显示进站停稳与车启动两个标记`() {
        val visible = LabScenarios.visible(commuteMode = true)
        assertEquals(LabScenarios.COMMUTE_MARK_IDS, visible.map { it.id }.toSet())
        assertTrue(visible.any { it.id == "enter_train_stop" })
        assertTrue(visible.any { it.id == "enter_train_depart" })
        // 其余一律隐藏（含乘车姿势 / 走路 / 扶梯等标定时代场景）
        assertEquals(2, visible.size)
        assertTrue(visible.none { it.id == "enter_ride_stand" })
        assertTrue(visible.none { it.id == "enter_walk_outdoor" })
        assertTrue(visible.none { it.id == "enter_escalator" })
    }

    @Test
    fun `关闭精简模式返回全量清单且顺序不变`() {
        assertEquals(LabScenarios.ALL, LabScenarios.visible(commuteMode = false))
        assertTrue("新场景进站停稳相邻（车启动锚点）", LabScenarios.ALL.any { it.id == "enter_train_depart" })
    }

    @Test
    fun `精简集合内的场景 id 均真实存在`() {
        LabScenarios.COMMUTE_MARK_IDS.forEach { id ->
            assertTrue("未知场景 id: $id", LabScenarios.byId.containsKey(id))
        }
    }
}
