package com.metrostop.reminder

import com.metrostop.reminder.core.feature.GaitGate
import com.metrostop.reminder.core.model.TuningConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步态门单测（v3）：gyro RMS 阈值 + confirm/release 时序 + 陀螺缺失降级。
 *
 * 标定依据（2026-09-28 lab 数据）：走路/楼梯 1.18+，站台静立 0.20，
 * 站姿乘车 0.11~0.14，坐姿玩手机 0.19 —— walkGyroTh=0.6 分界。
 */
class GaitGateTest {

    private val config = TuningConfig.Default

    /** 以 50 Hz 生成 tMs 序列（步长 20 ms） */
    private fun feed(gate: GaitGate, fromMs: Long, durSec: Double, gyro: Float, startAt: Long = 0L): Long {
        var t = fromMs
        val n = (durSec * 50).toInt()
        for (i in 0 until n) {
            gate.process(t, gyro)
            t += 20
        }
        return t
    }

    @Test
    fun `走路高陀螺_确认后判定步行`() {
        val gate = GaitGate(config)
        // 走路：gyro 1.2 → 1.5 s 确认后才置位
        var t = feed(gate, 0L, 1.0, gyro = 1.2f)
        assertFalse("确认期未满不应判步行", gate.isWalking)
        t = feed(gate, t, 1.0, gyro = 1.2f)
        assertTrue("持续走路应判步行", gate.isWalking)
    }

    @Test
    fun `静立低陀螺_不判步行`() {
        val gate = GaitGate(config)
        feed(gate, 0L, 5.0, gyro = 0.2f)
        assertFalse("站台静立不应判步行", gate.isWalking)
    }

    @Test
    fun `站姿乘车微动_不判步行`() {
        val gate = GaitGate(config)
        feed(gate, 0L, 10.0, gyro = 0.13f)
        assertFalse("站姿乘车（握手扶杆微动）不应判步行", gate.isWalking)
    }

    @Test
    fun `走出走路_释放容差后退出`() {
        val gate = GaitGate(config)
        var t = feed(gate, 0L, 3.0, gyro = 1.2f)
        assertTrue("应先进入步行", gate.isWalking)
        // 突然静止：release 容差 2 s 内不退出，超时才退出
        t = feed(gate, t, 1.0, gyro = 0.05f)
        assertTrue("释放容差内不应立刻退出", gate.isWalking)
        feed(gate, t, 2.0, gyro = 0.05f)
        assertFalse("超过释放容差应退出步行", gate.isWalking)
    }

    @Test
    fun `陀螺缺失_降级为不在走`() {
        val gate = GaitGate(config)
        feed(gate, 0L, 5.0, gyro = 0f)
        assertFalse("陀螺缺失（gyroMag=0）应降级为不在走", gate.isWalking)
    }

    @Test
    fun `走路中短暂停顿_不退出`() {
        val gate = GaitGate(config)
        var t = feed(gate, 0L, 3.0, gyro = 1.2f)
        assertTrue(gate.isWalking)
        // 过路口/等灯 1.5 s 停顿（< gaitReleaseSec 2 s）后又走
        t = feed(gate, t, 1.5, gyro = 0.1f)
        assertTrue("短暂停顿（<释放容差）不应退出步行", gate.isWalking)
        feed(gate, t, 2.0, gyro = 1.2f)
        assertTrue("恢复走路应保持步行态", gate.isWalking)
    }
}
