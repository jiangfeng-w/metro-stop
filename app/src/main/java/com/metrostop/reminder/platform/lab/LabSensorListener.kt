package com.metrostop.reminder.platform.lab

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/**
 * Lab 采集的传感器监听（与监测的 `SensorCollector` 完全独立、互不影响）：
 *
 * - **每流独立落行**：加速度计 / 线性加速度 / 陀螺仪 / 磁力计 / 旋转矢量各自的回调时刻分别
 *   写行（stream 列区分）——不合并成一行，避免重蹈监测 CSV「陀螺仪相位差 ~20 ms」的近似；
 * - 慢流（气压 / 光照 / 接近 / 计步）同样独立写行；行内自带 `t_ms`（elapsedRealtime）；
 * - 回调线程即 Lab 服务自己的 HandlerThread；热路径只做拼行 + trySend（写入在 LabRecorder 的 IO 队列）。
 */
class LabSensorListener(
    private val write: (stream: String, row: String) -> Unit,
) : SensorEventListener {

    private var sm: SensorManager? = null
    private val registered = mutableListOf<Sensor>()

    /**
     * 注册全部可用流。返回实际注册成功的传感器名列表（降级信息：设备没有的传感器不会出现）。
     * [onReady] 在注册完成后回调一次（sensorHandler 线程）。
     */
    fun start(sm: SensorManager, handler: Handler, onReady: (List<String>) -> Unit): List<String> {
        this.sm = sm
        // (传感器类型, stream 名, 采样周期 us)
        val wanted = listOf(
            Sensor.TYPE_ACCELEROMETER to "accel" to 20_000,      // 50 Hz
            Sensor.TYPE_LINEAR_ACCELERATION to "lin" to 20_000,  // 50 Hz
            Sensor.TYPE_GYROSCOPE to "gyro" to 20_000,           // 50 Hz
            Sensor.TYPE_MAGNETIC_FIELD to "mag" to 100_000,      // 10 Hz
            Sensor.TYPE_ROTATION_VECTOR to "rot" to 20_000,      // 50 Hz
            Sensor.TYPE_PRESSURE to "baro" to 200_000,           // 5 Hz
            Sensor.TYPE_LIGHT to "light" to 200_000,             // 5 Hz
            Sensor.TYPE_PROXIMITY to "prox" to 200_000,          // 5 Hz
            Sensor.TYPE_STEP_DETECTOR to "step_detector" to 0,   // 事件驱动
            Sensor.TYPE_STEP_COUNTER to "step_counter" to 200_000,
        )
        for ((pair, periodUs) in wanted) {
            val (type, stream) = pair
            val s = sm.getDefaultSensor(type) ?: continue
            val ok = runCatching { sm.registerListener(this, s, periodUs, handler) }.getOrDefault(false)
            if (ok) registered += s
        }
        onReady(registered.map { nameOf(it.type) })
        return registered.map { nameOf(it.type) }
    }

    fun stop() {
        runCatching { sm?.unregisterListener(this) }
        registered.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = SystemClock.elapsedRealtime()
        val utc = System.currentTimeMillis()
        val v = event.values
        val row = when (event.sensor.type) {
            // 气压：两列（pressure, alt_m）
            Sensor.TYPE_PRESSURE ->
                "$t,$utc,${fmt(v[0])},${fmt( SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, v[0]) )}"
            // 光照与接近分属两个传感器，但列相同（light_lx, prox）：只填自己那列
            Sensor.TYPE_LIGHT -> "$t,$utc,${fmt(v[0])},"
            Sensor.TYPE_PROXIMITY -> "$t,$utc,,${v[0].toInt()}"
            // 计步：detector=1 表示一次步事件；counter 为累计值
            Sensor.TYPE_STEP_DETECTOR -> "$t,$utc,1,"
            Sensor.TYPE_STEP_COUNTER -> "$t,$utc,,${v[0].toLong()}"
            // 五轴流：stream 列 + 三轴（多余维度忽略，rot 第四列（置信度）拼在后面）
            else -> {
                val stream = nameOf(event.sensor.type)
                val extra = if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR && v.size > 3) ",${fmt(v[3])}" else ""
                "$t,$utc,$stream,${fmt(v[0])},${fmt(v[1])},${fmt(v[2])}$extra"
            }
        }
        // 文件归属：baro/prox 归 baro/light 流（列已对齐表头），计步归 steps，其余归 imu
        val file = when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> "baro"
            Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY -> "light"
            Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER -> "steps"
            else -> "imu"
        }
        write(file, row)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun fmt(x: Float): String = String.format(java.util.Locale.US, "%.4f", x)

    companion object {
        fun nameOf(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_LINEAR_ACCELERATION -> "lin"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            Sensor.TYPE_MAGNETIC_FIELD -> "mag"
            Sensor.TYPE_ROTATION_VECTOR -> "rot"
            Sensor.TYPE_PRESSURE -> "baro"
            Sensor.TYPE_LIGHT -> "light"
            Sensor.TYPE_PROXIMITY -> "prox"
            Sensor.TYPE_STEP_DETECTOR -> "step_detector"
            Sensor.TYPE_STEP_COUNTER -> "step_counter"
            else -> "type_$type"
        }
    }
}
