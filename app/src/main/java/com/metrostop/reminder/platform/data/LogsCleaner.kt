package com.metrostop.reminder.platform.data

import android.content.Context
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.retention.CsvRetention
import com.metrostop.reminder.core.retention.CsvSessionScanner
import com.metrostop.reminder.core.retention.LogFileEntry
import com.metrostop.reminder.core.retention.RetentionPolicy
import java.io.File

/**
 * logs 目录会话级清理的 **IO 薄壳**：扫描 → 交给 `core/retention`（纯逻辑）选 → 删三件套。
 *
 * 会话识别与策略判定全在 core（JVM 可测），本类只做 `File` 列表映射与 `delete()`。
 * 删除范围**仅限**已识别的会话文件；`replay_report.txt`、未知文件一律不碰。
 * 只在 IO 协程里调用（不占服务启动关键路径）。
 */
class LogsCleaner(private val context: Context) {

    /** 占用快照：供设置卡片显示 */
    data class Usage(val sessions: Int, val files: Int, val bytes: Long) {
        fun readable(): String = "日志：%d 次会话 / 共 %s".format(sessions, readableSize(bytes))
    }

    private fun logsDir(): File = context.getExternalFilesDir("logs") ?: context.filesDir

    private fun entries(): List<LogFileEntry> {
        val files = logsDir().listFiles { f -> f.isFile } ?: return emptyList()
        return files.map { LogFileEntry(it.name, it.length(), it.lastModified()) }
    }

    /** 扫描会话（不删除） */
    fun scan(): Usage {
        val all = entries()
        val sessions = CsvSessionScanner.group(all)
        val kept = all.filter { CsvSessionScanner.stampOf(it.name) != null }
        return Usage(
            sessions = sessions.size,
            files = kept.size,
            bytes = kept.sumOf { it.sizeBytes },
        )
    }

    /**
     * 按策略清理一次。
     *
     * @param activeStamp 本次正在录制的会话 stamp（`CsvRecorder.baseName`），永不删
     * @return 删除的会话数；异常一律吞掉（清理失败不影响监测）
     */
    fun clean(config: TuningConfig, activeStamp: String? = null): Int = runCatching {
        val policy = RetentionPolicy.from(config)
        val sessions = CsvSessionScanner.group(entries())
        val doomed = CsvRetention.selectForDeletion(
            sessions = sessions,
            nowMs = System.currentTimeMillis(),
            policy = policy,
            activeStamp = activeStamp,
        )
        val dir = logsDir()
        var deleted = 0
        for (stamp in doomed) {
            if (deleteSession(dir, stamp)) deleted++
        }
        deleted
    }.getOrDefault(0)

    /**
     * 删掉目录里全部会话（调试面板「立即清理日志」用）。
     * @param exceptStamp 排除的会话（正在录制的），传 null 表示全删
     */
    fun cleanAll(exceptStamp: String? = null): Int = runCatching {
        val dir = logsDir()
        val stamps = CsvSessionScanner.group(entries()).map { it.stamp }
        var deleted = 0
        for (stamp in stamps) {
            if (stamp == exceptStamp) continue
            if (deleteSession(dir, stamp)) deleted++
        }
        deleted
    }.getOrDefault(0)

    /** 删除一个会话的文件组（sensor/events/cell/meta，存在的才删）；全部成功才算整会话删除成功 */
    private fun deleteSession(dir: File, stamp: String): Boolean {
        val names = listOf("sensor_$stamp.csv", "events_$stamp.csv", "cell_$stamp.csv", "${stamp}_meta.json")
        var allOk = true
        for (n in names) {
            val f = File(dir, n)
            if (!f.exists()) continue
            if (!f.delete()) allOk = false
        }
        return allOk
    }

    companion object {
        fun readableSize(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
