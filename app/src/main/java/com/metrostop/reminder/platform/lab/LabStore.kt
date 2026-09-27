package com.metrostop.reminder.platform.lab

import android.content.Context
import com.metrostop.reminder.core.lab.LabFiles
import com.metrostop.reminder.core.lab.LabRetention
import com.metrostop.reminder.core.lab.LabRetentionPolicy
import com.metrostop.reminder.core.lab.LabSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * lab 目录的 IO 薄壳（镜像 `LogsCleaner` 与 `CsvRecorder` 的分工）：
 * 目录扫描 → core/lab（纯逻辑）选 → 删整个 `lab_*` 子目录。
 * 只碰 `lab_` 前缀目录，监测 CSV（`sensor_/events_/_meta`）与 `replay_report.txt` 一律不动。
 */
class LabStore(private val context: Context) {

    fun logsDir(): File = context.getExternalFilesDir("logs") ?: context.filesDir

    fun sessionDir(stamp: String): File = File(logsDir(), "${LabFiles.DIR_PREFIX}$stamp")

    fun newStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 扫描现有 lab 会话（不删除） */
    fun sessions(): List<LabSession> {
        val dirs = logsDir().listFiles { f -> f.isDirectory && LabFiles.stampOfDir(f.name) != null }
            ?: return emptyList()
        return dirs.map { d ->
            val files = d.listFiles { f -> f.isFile } ?: emptyArray()
            LabSession(
                stamp = LabFiles.stampOfDir(d.name)!!,
                totalBytes = files.sumOf { it.length() },
                lastModifiedMs = files.maxOfOrNull { it.lastModified() } ?: d.lastModified(),
            )
        }
    }

    /** lab 会话总占用（字节） */
    fun totalBytes(): Long = sessions().sumOf { it.totalBytes }

    /** 按策略清理（启动新采集时调；失败吞掉不影响采集） */
    fun clean(activeStamp: String? = null, policy: LabRetentionPolicy = LabRetentionPolicy.Default): Int =
        runCatching {
            val doomed = LabRetention.selectForDeletion(
                sessions = sessions(),
                nowMs = System.currentTimeMillis(),
                policy = policy,
                activeStamp = activeStamp,
            )
            var deleted = 0
            for (stamp in doomed) {
                if (deleteSession(stamp)) deleted++
            }
            deleted
        }.getOrDefault(0)

    /** 删除整个 lab 会话目录（递归）；目录不存在返回 true */
    fun deleteSession(stamp: String): Boolean {
        val dir = sessionDir(stamp)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }
}
