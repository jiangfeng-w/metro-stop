package com.metrostop.reminder.platform.data

import android.content.Context
import android.os.Build
import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorState
import com.metrostop.reminder.core.model.MotionSample
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 三件套异步写盘（总纲 7.2）：`sensor_*.csv` / `events_*.csv` / `*_meta.json`。
 *
 * 存放 `getExternalFilesDir("logs")`（免存储权限，adb pull 可取）。
 * 设计：单写线程 + Channel 队列，传感器 50 Hz 调用不阻塞；队列满时丢弃并计数（不卡采集链路）。
 */
class CsvRecorder(
    context: Context,
    private val route: RouteSpec,
    private val config: TuningConfig,
    scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext

    /** 独立 IO 作用域：不随服务 scope 取消，保证收尾时数据能落盘 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File = context.getExternalFilesDir("logs") ?: context.filesDir
    private val stamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val sensorFile = File(dir, "sensor_$stamp.csv")
    private val eventsFile = File(dir, "events_$stamp.csv")
    private val cellFile = File(dir, "cell_$stamp.csv")
    private val metaFile = File(dir, "${stamp}_meta.json")

    private val queue = Channel<String>(capacity = 4096)
    private var dropped = 0
    private var sensorWriter: BufferedWriter? = null
    private var eventsWriter: BufferedWriter? = null
    private var cellWriter: BufferedWriter? = null
    private var writerJob: kotlinx.coroutines.Job? = null
    private val startedAtMs = System.currentTimeMillis()

    val baseName: String get() = stamp
    val logsDir: String get() = dir.absolutePath

    fun start() {
        dir.mkdirs()
        // 表头与 meta **同步写**：即使服务随即被结束 / 进程被回收，文件也有可读内容
        // （此前表头由写线程写入，服务短命时会留下 0 字节文件）。
        runCatching {
            sensorFile.writeText("t_ms,utc_ms,ax,ay,az,lx,ly,lz,gx,gy,gz,vib,h,state,n\n")
            eventsFile.writeText("t_ms,type,station_index,station_name,dwell_s,interval_s,note\n")
            // v4 蜂窝小区流（可选第四件；格式与 lab cellid 一致，离线回放可直接复用）
            cellFile.writeText("t_ms,utc_ms,cells\n")
            writeMeta()
        }
        writerJob = ioScope.launch {
            // **append 模式**：`bufferedWriter()` 会截断文件，把上面同步写的表头清掉。
            sensorWriter = java.io.FileOutputStream(sensorFile, true).bufferedWriter()
            eventsWriter = java.io.FileOutputStream(eventsFile, true).bufferedWriter()
            cellWriter = java.io.FileOutputStream(cellFile, true).bufferedWriter()
            // 批量落盘：每 5 行（≈100 ms）flush 一次。
            // 取 5 而非 50：磁贴等短会话可能只跑几秒就结束，间隔太大会丢尾部数据。
            var sinceFlush = 0
            for (line in queue) {
                when {
                    line.startsWith(SENSOR_PREFIX) -> sensorWriter?.appendLine(line.removePrefix(SENSOR_PREFIX))
                    line.startsWith(CELL_PREFIX) -> cellWriter?.appendLine(line.removePrefix(CELL_PREFIX))
                    else -> eventsWriter?.appendLine(line)
                }
                if (++sinceFlush >= 5) {
                    sensorWriter?.flush()
                    eventsWriter?.flush()
                    cellWriter?.flush()
                    sinceFlush = 0
                }
            }
            sensorWriter?.flush()
            eventsWriter?.flush()
            cellWriter?.flush()
            runCatching { sensorWriter?.close() }
            runCatching { eventsWriter?.close() }
            runCatching { cellWriter?.close() }
        }
    }

    /** 传感器样本（50 Hz 热路径：只入队，不做 IO） */
    fun onSample(s: MotionSample, vib: Float, h: Float, state: DetectorState, n: Int) {
        val line = buildString(96) {
            append(SENSOR_PREFIX)
            append(s.tMs).append(',')
            append(s.utcMs).append(',')
            append(fmt(s.ax)).append(',')
            append(fmt(s.ay)).append(',')
            append(fmt(s.az)).append(',')
            append(fmtOrEmpty(s.lx)).append(',')
            append(fmtOrEmpty(s.ly)).append(',')
            append(fmtOrEmpty(s.lz)).append(',')
            append(fmtOrEmpty(s.gx)).append(',')
            append(fmtOrEmpty(s.gy)).append(',')
            append(fmtOrEmpty(s.gz)).append(',')
            append(fmt(vib)).append(',')
            append(fmt(h)).append(',')
            append(state.name).append(',')
            append(n)
        }
        if (queue.trySend(line).isFailure) dropped++
    }

    /** 事件行 */
    fun onEvent(e: DetectorEvent) {
        val line = buildString(64) {
            append(e.tMs).append(',')
            append(e.type.name).append(',')
            append(e.stationIndex ?: "").append(',')
            append(e.stationName ?: "").append(',')
            append(e.dwellS?.let { fmt(it) } ?: "").append(',')
            append(e.intervalS?.let { fmt(it) } ?: "").append(',')
            append(e.note ?: "")
        }
        queue.trySend(line)
    }

    /** 蜂窝小区流一行（v4；cells 原始串 `pci:ci:rssi|...`，主服务排首，与 lab cellid 同格式） */
    fun onCellRow(tMs: Long, utcMs: Long, cells: String) {
        queue.trySend("$CELL_PREFIX$tMs,$utcMs,$cells")
    }

    private fun writeMeta() {
        val battery = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)

        val json = buildString(512) {
            append("{\n")
            append("  \"app\": \"到站了\",\n")
            append("  \"version\": \"").append("0.1.0").append("\",\n")
            append("  \"fileStamp\": \"").append(stamp).append("\",\n")
            append("  \"startedAt\": ").append(startedAtMs).append(",\n")
            append("  \"model\": \"").append(Build.MODEL).append("\",\n")
            append("  \"device\": \"").append(Build.DEVICE).append("\",\n")
            append("  \"android\": \"").append(Build.VERSION.RELEASE).append("\",\n")
            append("  \"sdkInt\": ").append(Build.VERSION.SDK_INT).append(",\n")
            append("  \"batteryPctAtStart\": ").append(battery).append(",\n")
            append("  \"line\": \"").append(route.lineName).append("\",\n")
            append("  \"direction\": \"").append(route.directionName).append("\",\n")
            append("  \"boarding\": \"").append(route.boardingStation.name).append("\",\n")
            append("  \"destination\": \"").append(route.destinationStation.name).append("\",\n")
            append("  \"stopCount\": ").append(route.stopCount).append(",\n")
            append("  \"tuning\": {\n")
            append("    \"targetSampleHz\": ").append(config.targetSampleHz).append(",\n")
            append("    \"vibRunTh\": ").append(config.vibRunTh).append(",\n")
            append("    \"vibStopTh\": ").append(config.vibStopTh).append(",\n")
            append("    \"brakeAccelTh\": ").append(config.brakeAccelTh).append(",\n")
            append("    \"brakeMinSec\": ").append(config.brakeMinSec).append(",\n")
            append("    \"brakeMaxSec\": ").append(config.brakeMaxSec).append(",\n")
            append("    \"brakeReleaseSec\": ").append(config.brakeReleaseSec).append(",\n")
            append("    \"stillConfirmSec\": ").append(config.stillConfirmSec).append(",\n")
            append("    \"departConfirmSec\": ").append(config.departConfirmSec).append(",\n")
            append("    \"minStopIntervalSec\": ").append(config.minStopIntervalSec).append(",\n")
            append("    \"slowLpfHz\": ").append(config.slowLpfHz).append(",\n")
            append("    \"vibLowHz\": ").append(config.vibLowHz).append(",\n")
            append("    \"vibHighHz\": ").append(config.vibHighHz).append(",\n")
            append("    \"vibWindowSec\": ").append(config.vibWindowSec).append(",\n")
            append("    \"gravityLpfHz\": ").append(config.gravityLpfHz).append(",\n")
            append("    \"startGraceSec\": ").append(config.startGraceSec).append(",\n")
            append("    \"maxMonitorMin\": ").append(config.maxMonitorMin).append(",\n")
            append("    \"endGraceSec\": ").append(config.endGraceSec).append(",\n")
            append("    \"dataGapSec\": ").append(config.dataGapSec).append("\n")
            append("  }\n")
            append("}\n")
        }
        metaFile.writeText(json)
    }

    /**
     * 收尾：关闭队列并等待写线程落盘（服务 stopSelf 前 / onDestroy 中调用）。
     *
     * 队列用 `close()` 让 `for (line in queue)` 正常排空；超时后仍尝试 flush，尽量不丢数据。
     * 幂等：重复调用安全（第二次 join 已完成的 job 立即返回）。
     *
     * @param timeoutMs 等待上限；teardown 走后台线程用默认 3 s，
     *                  onDestroy（主线程）用较短值避免 ANR 风险。
     */
    fun stop(timeoutMs: Long = 3000) {
        queue.close()
        val job = writerJob
        if (job != null && job.isActive) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { job.join() }
            }
        }
        runCatching {
            sensorWriter?.flush()
            eventsWriter?.flush()
        }
    }

    fun droppedLines(): Int = dropped

    private fun fmt(v: Float): String = String.format(Locale.US, "%.4f", v)
    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
    private fun fmtOrEmpty(v: Float?): String = v?.let { fmt(it) } ?: ""

    companion object {
        private const val SENSOR_PREFIX = "S|"
        private const val CELL_PREFIX = "C|"
    }
}
