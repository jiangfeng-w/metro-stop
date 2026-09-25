package com.metrostop.reminder.core.feature

import com.metrostop.reminder.core.model.TuningConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * RBJ cookbook 二阶 IIR（Direct Form I），因果实现 —— 同一份 CSV 回放结果 = 现场行为。
 * 采样率变化超过 10% 时重建系数（保证降采样 / 抖动输入下仍稳定）。
 */
class Biquad(
    private val kind: Kind,
    private var freqHz: Double,
    private var q: Double = 0.7071,
    var sampleRateHz: Double = 50.0,
) {
    enum class Kind { LOW_PASS, HIGH_PASS }

    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        design()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    /** 采样率变化时重建系数（内部会 reset，避免旧状态跨采样率污染） */
    fun updateSampleRate(fs: Double) {
        if (fs <= 0.0) return
        if (abs(fs - sampleRateHz) / sampleRateHz < 0.10) return
        sampleRateHz = fs
        design()
        reset()
    }

    private fun design() {
        val w0 = 2.0 * Math.PI * (freqHz / sampleRateHz).coerceIn(1e-6, 0.49)
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        when (kind) {
            Kind.LOW_PASS -> {
                b0 = (1.0 - cosW) / 2.0 / a0
                b1 = (1.0 - cosW) / a0
                b2 = b0
            }
            Kind.HIGH_PASS -> {
                b0 = (1.0 + cosW) / 2.0 / a0
                b1 = -(1.0 + cosW) / a0
                b2 = b0
            }
        }
        a1 = -2.0 * cosW / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/**
 * 特征提取（总纲 5.1）：
 * - `H` = 去重力后水平分量幅值，经 0.4 Hz 低通 → 列车纵向加减速（刹车判据）；
 * - `vib` = 去重力线性加速度幅值经 3–20 Hz 带通 + 1 s RMS 窗 → 行驶 / 停稳判据。
 *
 * 完全因果：只依赖当前样本与内部历史状态，因此 CSV 回放与现场实时结果一致。
 * 重力方向用 0.05 Hz 低通估计（手机任意朝向均可，与朝向无关）。
 */
class FeatureExtractor(private val config: TuningConfig) {

    private val gravityLpf = Biquad(Biquad.Kind.LOW_PASS, config.gravityLpfHz, config.iirQ, 50.0)
    private val hLpf = Biquad(Biquad.Kind.LOW_PASS, config.slowLpfHz, config.iirQ, 50.0)
    private val vibHp = Biquad(Biquad.Kind.HIGH_PASS, config.vibLowHz, config.iirQ, 50.0)
    private val vibLp = Biquad(Biquad.Kind.LOW_PASS, config.vibHighHz, config.iirQ, 50.0)

    private val window = RingBuffer((config.targetSampleHz * config.vibWindowSec).toInt().coerceAtLeast(1))

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gravityInit = false
    private var lastTMs = 0L

    var lastVib: Float = 0f
        private set
    var lastH: Float = 0f
        private set

    fun reset() {
        gravityLpf.reset()
        hLpf.reset()
        vibHp.reset()
        vibLp.reset()
        window.clear()
        gravityInit = false
        lastTMs = 0L
        lastVib = 0f
        lastH = 0f
    }

    fun process(sample: com.metrostop.reminder.core.model.MotionSample): com.metrostop.reminder.core.model.Features {
        // 采样率自适应（用相邻样本 dt 平滑估计）
        val dtMs = if (lastTMs == 0L) 0L else sample.tMs - lastTMs
        lastTMs = sample.tMs
        if (dtMs in 1..2000) {
            val fs = 1000.0 / dtMs
            gravityLpf.updateSampleRate(fs)
            hLpf.updateSampleRate(fs)
            vibHp.updateSampleRate(fs)
            vibLp.updateSampleRate(fs)
        }

        // 1) 重力方向估计（0.05 Hz 低通，慢于一切列车运动）
        if (!gravityInit) {
            gx = sample.ax.toDouble()
            gy = sample.ay.toDouble()
            gz = sample.az.toDouble()
            gravityInit = true
        } else {
            gx = gravityLpf.process(sample.ax.toDouble())
            gy = gravityLpf.process(sample.ay.toDouble())
            gz = gravityLpf.process(sample.az.toDouble())
        }
        val gNorm = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        val gravOk = gNorm > 1e-3

        // 2) 去重力线性加速度：优先用系统 TYPE_LINEAR_ACCELERATION，缺失则 a - g
        val linX: Double
        val linY: Double
        val linZ: Double
        if (sample.hasLinear) {
            linX = sample.lx!!.toDouble()
            linY = sample.ly!!.toDouble()
            linZ = sample.lz!!.toDouble()
        } else {
            linX = sample.ax - gx
            linY = sample.ay - gy
            linZ = sample.az - gz
        }

        // 3) 去除重力方向分量 → 水平分量幅值（与手机朝向无关）
        val horiz: Double = if (gravOk) {
            val ux = gx / gNorm
            val uy = gy / gNorm
            val uz = gz / gNorm
            val along = linX * ux + linY * uy + linZ * uz // 沿重力方向的分量
            val hx = linX - along * ux
            val hy = linY - along * uy
            val hz = linZ - along * uz
            kotlin.math.sqrt(hx * hx + hy * hy + hz * hz)
        } else {
            kotlin.math.sqrt(linX * linX + linY * linY + linZ * linZ)
        }

        // 4) H：0.4 Hz 低通（≈1 s 均值）
        val h = hLpf.process(horiz)

        // 5) vib：3–20 Hz 带通 RMS（1 s 窗）
        val mag = kotlin.math.sqrt(linX * linX + linY * linY + linZ * linZ)
        val bp = vibLp.process(vibHp.process(mag))
        window.push(bp.toFloat())
        val vib = window.rms()

        lastH = h.toFloat()
        lastVib = vib
        return com.metrostop.reminder.core.model.Features(tMs = sample.tMs, h = lastH, vib = lastVib)
    }
}
