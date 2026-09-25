package com.metrostop.reminder.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.metrostop.reminder.core.model.MotionSample

/**
 * 传感器采集（总纲技术选型 + 硬性规则 3）：
 * - 首选 `TYPE_LINEAR_ACCELERATION`（已去重力），无则降级 `TYPE_ACCELEROMETER`（core 里自减重力）；
 * - 采样 50 Hz（`SENSOR_DELAY_GAME` ≈ 50 Hz，并记录实际间隔供特征层自适应采样率）；
 * - 独立 HandlerThread 回调，不占主线程；陀螺仪有则附带记录（CSV 用，不参与判定）。
 */
class SensorCollector(
    context: Context,
    private val onSample: (MotionSample) -> Unit,
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("SensorCollector").apply { start() }
    private val handler = Handler(thread.looper)

    private var linear: Sensor? = null
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var usingLinear = false

    /** 两路同时注册时，用其区分 / 合并（避免同一物理量重复上报两次） */
    private var lastAccelMs = 0L
    private var lastLinearMs = 0L

    /** 实际采样率估计（Hz），调试面板 / 断流判断参考 */
    @Volatile
    var measuredHz: Double = 0.0
        private set

    @Volatile
    private var lastSampleMs = 0L

    private var recentCount = 0
    private var recentWindowStartMs = 0L

    val usingLinearAcceleration: Boolean get() = usingLinear

    fun start(): Boolean {
        linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        usingLinear = linear != null
        val primary = linear ?: accel ?: return false
        val rate = SensorManager.SENSOR_DELAY_GAME // ≈50 Hz
        sm.registerListener(this, primary, rate, handler)
        gyro?.let { sm.registerListener(this, it, rate, handler) }
        return true
    }

    /** 是否拿到了线性加速度传感器（false = 降级模式） */
    fun hasLinearSensor(): Boolean = linear != null

    fun stop() {
        runCatching { sm.unregisterListener(this) }
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastLinearMs = now
                emit(now, event, hasLinear = true)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // 若线性加速度可用，则忽略加速度计（避免重复）；仅作降级来源
                if (linear != null) return
                lastAccelMs = now
                emit(now, event, hasLinear = false)
            }

            Sensor.TYPE_GYROSCOPE -> {
                // 陀螺仪单独到达，先缓存；简化处理：丢弃（CSV 里 gx/gy/gz 由加速度样本携带时才有值）
                pendingGyro[0] = event.values[0]
                pendingGyro[1] = event.values[1]
                pendingGyro[2] = event.values[2]
                hasGyro = true
            }
        }
    }

    private val pendingGyro = FloatArray(3)
    private var hasGyro = false

    private fun emit(nowMs: Long, event: SensorEvent, hasLinear: Boolean) {
        val utc = System.currentTimeMillis()
        // 采样率估计（1 s 窗口）
        if (recentWindowStartMs == 0L) recentWindowStartMs = nowMs
        recentCount++
        if (nowMs - recentWindowStartMs >= 1000L) {
            measuredHz = recentCount * 1000.0 / (nowMs - recentWindowStartMs)
            recentCount = 0
            recentWindowStartMs = nowMs
        }
        lastSampleMs = nowMs

        val s = if (hasLinear) {
            MotionSample(
                tMs = nowMs,
                utcMs = utc,
                ax = event.values[0],
                ay = event.values[1],
                az = event.values[2],
                lx = event.values[0],
                ly = event.values[1],
                lz = event.values[2],
                gx = if (hasGyro) pendingGyro[0] else null,
                gy = if (hasGyro) pendingGyro[1] else null,
                gz = if (hasGyro) pendingGyro[2] else null,
            )
        } else {
            MotionSample(
                tMs = nowMs,
                utcMs = utc,
                ax = event.values[0],
                ay = event.values[1],
                az = event.values[2],
                gx = if (hasGyro) pendingGyro[0] else null,
                gy = if (hasGyro) pendingGyro[1] else null,
                gz = if (hasGyro) pendingGyro[2] else null,
            )
        }
        onSample(s)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
