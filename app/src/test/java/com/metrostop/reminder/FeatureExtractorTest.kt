package com.metrostop.reminder

import com.metrostop.reminder.core.feature.FeatureExtractor
import com.metrostop.reminder.core.model.MotionSample
import com.metrostop.reminder.core.model.TuningConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 特征提取数值验证：静止 / 稳态振动 / 低频制动三类合成信号。
 * 断言用宽松范围（判定的是「量级正确、方向正确」，不是精确数值）。
 */
class FeatureExtractorTest {

    private val config = TuningConfig.Default
    private val fs = 50.0

    private fun sample(i: Int, ax: Double, ay: Double, az: Double): MotionSample {
        val t = i.toLong() * 20L
        return MotionSample(
            tMs = t,
            utcMs = 1_700_000_000_000L + t,
            ax = ax.toFloat(),
            ay = ay.toFloat(),
            az = az.toFloat(),
            lx = ax.toFloat(),
            ly = ay.toFloat(),
            lz = 0f, // 手机平放：z 轴为重力方向，水平面为 x-y
        )
    }

    @Test
    fun `静止时 vib 接近 0 且 H 接近 0`() {
        val ex = FeatureExtractor(config)
        var last = ex.process(sample(0, 0.0, 0.0, 9.8))
        repeat(250) { i -> last = ex.process(sample(i + 1, 0.0, 0.0, 9.8)) }
        assertTrue("静止 vib 应接近 0，实际 ${last.vib}", last.vib < config.vibStopTh)
        assertTrue("静止 H 应接近 0，实际 ${last.h}", last.h < 0.1f)
    }

    @Test
    fun `持续振动时 vib 高于运行阈值`() {
        val ex = FeatureExtractor(config)
        var last = ex.process(sample(0, 0.0, 0.0, 9.8))
        // 10 Hz 横向正弦，幅值 1.5 m/s²（车厢振动量级）
        repeat(500) { i ->
            val v = 1.5 * sin(2 * PI * 10.0 * i / fs)
            last = ex.process(sample(i + 1, v, 0.0, 9.8))
        }
        assertTrue("振动 vib 应高于运行阈值，实际 ${last.vib}", last.vib > config.vibRunTh)
    }

    @Test
    fun `缓慢制动产生可见 H`() {
        val ex = FeatureExtractor(config)
        var last = ex.process(sample(0, 0.0, 0.0, 9.8))
        // 1 m/s² 恒定横向（模拟纵向制动）持续 5 s：低通后应收敛到接近 1
        repeat(250) { i -> last = ex.process(sample(i + 1, 1.0, 0.0, 9.8)) }
        assertTrue("制动 H 应高于阈值，实际 ${last.h}", last.h > config.brakeAccelTh)
        assertTrue("制动 H 不应远超输入幅值，实际 ${last.h}", last.h < 1.2f)
    }

    @Test
    fun `制动结束后 H 回落`() {
        val ex = FeatureExtractor(config)
        var last = ex.process(sample(0, 0.0, 0.0, 9.8))
        repeat(250) { i -> last = ex.process(sample(i + 1, 1.0, 0.0, 9.8)) }
        repeat(400) { i -> last = ex.process(sample(251 + i, 0.0, 0.0, 9.8)) }
        assertTrue("制动结束后 H 应回落，实际 ${last.h}", last.h < config.brakeAccelTh)
    }

    @Test
    fun `同一份输入两次回放结果完全一致`() {
        val a = FeatureExtractor(config)
        val b = FeatureExtractor(config)
        val rows = (0 until 300).map { i ->
            val v = 0.8 * sin(2 * PI * 8.0 * i / fs)
            sample(i, v, 0.1, 9.8)
        }
        val ha = rows.map { a.process(it).h }
        val hb = rows.map { b.process(it).h }
        assertTrue("因果实现应可复现", ha == hb)
    }
}
