package com.metrostop.reminder.core.lab

/**
 * 实验室采集（lab-data-collection）的文件约定与保留策略 —— **纯 Kotlin，零 android.***。
 *
 * 与监测 CSV（`sensor_/events_/_meta` 三件套，见 `core/retention/CsvSessionScanner`）完全解耦：
 * 一次采集 = `logs/lab_<stamp>/` 一个独立子目录，内含 `lab_<stream>.csv` 多个流文件 + `lab_meta.json`。
 * 现有 `CsvSessionScanner.stampOf` 对 `lab_*` 文件返回 null → **监测保留策略天然不碰 Lab 数据**；
 * 反之本文件的 [LabRetention] 也只认 `lab_` 前缀目录，不碰监测 CSV。
 *
 * 采集参数（采样率等）不进 `TuningConfig`（红线期间该文件零改动），以常量形式由 platform 层持有。
 */

/** 一次实验室采集会话（目录级） */
data class LabSession(
    /** 目录名中的 stamp：`yyyyMMdd_HHmmss` */
    val stamp: String,
    /** 目录内现存文件字节数合计 */
    val totalBytes: Long,
    /** 目录内最新修改时间（调用方传 UTC 毫秒） */
    val lastModifiedMs: Long,
)

/** lab 目录名 → stamp；非 lab 目录（包括 `logs` 下散落的监测三件套）返回 null */
object LabFiles {

    const val DIR_PREFIX = "lab_"
    // 注意：**不能**叫 `lab_meta.json` —— 监测扫描器 `CsvSessionScanner.stampOf` 以
    // `endsWith("_meta.json")` 识别会话，会把它误判成 stamp="lab" 的监测会话（LabFilesTest 回归）。
    // 叫 `meta.json` 则与监测三件套模式完全不匹配。
    const val META_NAME = "meta.json"
    const val CSV_PREFIX = "lab_"
    const val CSV_SUFFIX = ".csv"

    /**
     * 采集流名（= 文件名 `lab_<stream>.csv`）。
     * `wifi` / `cellid` 为 cellular-wifi-fingerprint-validate 需求新增（BSSID 哈希快照 + 小区切换序列）。
     */
    val STREAMS = listOf("imu", "baro", "light", "loc", "gnss", "steps", "cell", "wifi", "cellid", "events")

    /** `lab_20260928_073000` → `20260928_073000`；不匹配返回 null */
    fun stampOfDir(dirName: String): String? = when {
        dirName.startsWith(DIR_PREFIX) ->
            dirName.removePrefix(DIR_PREFIX).ifEmpty { null }
        else -> null
    }

    /** 流文件名 → 流名；`lab_imu.csv` → `imu`；非本会话流文件返回 null */
    fun streamOf(fileName: String): String? = when {
        fileName == META_NAME -> null
        fileName.startsWith(CSV_PREFIX) && fileName.endsWith(CSV_SUFFIX) ->
            fileName.removePrefix(CSV_PREFIX).removeSuffix(CSV_SUFFIX).ifEmpty { null }
        else -> null
    }
}

/** lab 保留策略（独立于监测 CSV 的 `TuningConfig.retention*`；采集是临时需求，额度收紧） */
data class LabRetentionPolicy(
    val maxSessions: Int = 3,
    val maxDays: Int = 7,
) {
    companion object {
        /** 采集是短期调试行为：最多 3 次 / 7 天，不设体积上限（单次 ~40 MB，量级可控） */
        val Default = LabRetentionPolicy()
    }
}

/**
 * 保留策略选择（纯函数，JVM 可测；镜像 `CsvRetention.selectForDeletion` 的语义但更简单）：
 * 1. 年龄：早于 `now − maxDays` 的会话淘汰；
 * 2. 次数：保留最新 `maxSessions` 次。
 * [activeStamp]（正在采集的会话）永不删、但占名额。返回最旧在前。
 */
object LabRetention {

    fun selectForDeletion(
        sessions: List<LabSession>,
        nowMs: Long,
        policy: LabRetentionPolicy = LabRetentionPolicy.Default,
        activeStamp: String? = null,
    ): List<String> {
        val newestFirst = sessions
            .distinctBy { it.stamp }
            .sortedWith(compareByDescending<LabSession> { it.lastModifiedMs }.thenByDescending { it.stamp })

        val cutoffMs = nowMs - policy.maxDays.toLong() * 24L * 3600_000L
        val doomed = LinkedHashSet<String>()
        val alive = ArrayList<LabSession>(newestFirst.size)

        for (s in newestFirst) {
            if (s.stamp != activeStamp && s.lastModifiedMs < cutoffMs) doomed += s.stamp else alive += s
        }
        alive.forEachIndexed { i, s ->
            if (i < policy.maxSessions || s.stamp == activeStamp) return@forEachIndexed
            doomed += s.stamp
        }
        return doomed.sorted()
    }
}
