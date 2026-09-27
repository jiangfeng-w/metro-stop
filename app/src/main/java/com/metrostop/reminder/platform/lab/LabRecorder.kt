package com.metrostop.reminder.platform.lab

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.metrostop.reminder.core.lab.LabFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream

/**
 * 一次采集的多流 CSV 写盘（镜像 `CsvRecorder` 的设计，目录换成 `logs/lab_<stamp>/`）：
 * - 表头 **同步写**（服务短命 / 进程被杀时文件仍有可读内容）；
 * - 追加模式 + Channel 队列 + 独立 IO scope（50 Hz 热路径只 trySend，不阻塞回调线程）；
 * - 队满丢弃并计数（保采集不卡）；
 * - 停止时 `close()` 队列、等待写线程落盘（幂等）。
 *
 * 每流一个文件：`lab_imu.csv` / `lab_loc.csv` / …，行格式由调用方拼好（本类不关心列）。
 */
class LabRecorder(
    context: Context,
    stamp: String,
) {
    private val dir: File = File(
        (context.getExternalFilesDir("logs") ?: context.filesDir),
        "${LabFiles.DIR_PREFIX}$stamp",
    )
    private val stampId: String = stamp

    /** 独立 IO 作用域：不随服务 scope 取消，保证收尾时数据能落盘 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = 4096)

    @Volatile
    private var dropped = 0
    private val writers = HashMap<String, BufferedWriter>()
    private var writerJob: kotlinx.coroutines.Job? = null
    @Volatile
    var linesWritten = 0L
        private set

    val baseName: String get() = stampId
    val logsDir: String get() = dir.absolutePath

    /** 每流文件的表头；必须在 start 前填好 */
    fun start(headers: Map<String, String>) {
        dir.mkdirs()
        // 表头 + meta 同步写（附录：LabFiles.META_NAME）
        runCatching {
            for ((stream, header) in headers) {
                File(dir, "${LabFiles.CSV_PREFIX}$stream${LabFiles.CSV_SUFFIX}").writeText(header + "\n")
            }
            writeMeta(headers)
        }
        writerJob = ioScope.launch {
            for (line in queue) {
                // 行格式：<stream>|<csv row>
                val sep = line.indexOf('|')
                if (sep <= 0) continue
                val stream = line.substring(0, sep)
                val payload = line.substring(sep + 1)
                val w = writers.getOrPut(stream) {
                    java.io.FileOutputStream(
                        File(dir, "${LabFiles.CSV_PREFIX}$stream${LabFiles.CSV_SUFFIX}"), true,
                    ).bufferedWriter()
                }
                w.appendLine(payload)
                linesWritten++
                // 批量落盘：每 8 行 flush 一次（lab 流合计 ~200 行/s，≈40ms 一刷）
                if (linesWritten % 8L == 0L) {
                    runCatching { w.flush() }
                }
            }
            writers.values.forEach { runCatching { it.flush() } }
            writers.values.forEach { runCatching { it.close() } }
        }
    }

    /** 写一行（传感器回调线程调用：只 trySend） */
    fun write(stream: String, row: String) {
        if (queue.trySend("$stream|$row").isFailure) dropped++
    }

    /** 事件行（lab_events 流的简写） */
    fun writeEvent(type: String, detail: String) {
        write("events", eventRow(type, detail))
    }

    fun droppedLines(): Int = dropped

    /** 收尾：关队列等落盘（幂等） */
    fun stop(timeoutMs: Long = 3000) {
        queue.close()
        val job = writerJob
        if (job != null && job.isActive) {
            runBlocking {
                withTimeoutOrNull(timeoutMs) { job.join() }
            }
        }
        writers.values.forEach {
            runCatching { it.flush() }
            runCatching { it.close() }
        }
    }

    private fun eventRow(type: String, detail: String): String {
        val t = SystemClock.elapsedRealtime()
        val utc = System.currentTimeMillis()
        return "$t,$utc,$type,$detail"
    }

    private fun writeMeta(headers: Map<String, String>) {
        val meta = buildString(256) {
            append("{\n")
            append("  \"app\": \"到站了\",\n")
            append("  \"kind\": \"lab_collection\",\n")
            append("  \"fileStamp\": \"").append(stampId).append("\",\n")
            append("  \"streams\": [").append(headers.keys.joinToString(",") { "\"$it\"" }).append("],\n")
            append("  \"model\": \"").append(Build.MODEL).append("\",\n")
            append("  \"android\": \"").append(Build.VERSION.RELEASE).append("\",\n")
            append("  \"sdkInt\": ").append(Build.VERSION.SDK_INT).append("\n")
            append("}\n")
        }
        File(dir, LabFiles.META_NAME).writeText(meta)
    }
}
