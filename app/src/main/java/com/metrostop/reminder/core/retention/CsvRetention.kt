package com.metrostop.reminder.core.retention

import com.metrostop.reminder.core.model.TuningConfig

/**
 * logs 目录里的一个文件（纯数据快照，不持有 `File`；core 禁 android.* 也不碰 IO）。
 * 目录扫描在 `platform/data/LogsCleaner`，分组 / 选择逻辑全在本文件（JVM 可测）。
 */
data class LogFileEntry(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * 一个 CSV 会话（三件套同组）：`sensor_<stamp>.csv` / `events_<stamp>.csv` / `<stamp>_meta.json`。
 */
data class CsvSession(
    /** 会话标识 `yyyyMMdd_HHmmss`，也是文件名中缀 */
    val stamp: String,
    /** 组内**现存**文件字节数合计（容错：缺事件的会话只算现存的） */
    val totalBytes: Long,
    /** 组内最新修改时间（UTC 毫秒），用于「保留最近 D 天」 */
    val lastModifiedMs: Long,
)

/** 保留策略；数值唯一来源见 `TuningConfig.retention*` */
data class RetentionPolicy(
    val maxSessions: Int,
    val maxDays: Int,
    val maxTotalBytes: Long,
) {
    companion object {
        fun from(config: TuningConfig): RetentionPolicy = RetentionPolicy(
            maxSessions = config.retentionSessions,
            maxDays = config.retentionDays,
            maxTotalBytes = config.retentionMaxMb.toLong() * 1024L * 1024L,
        )
    }
}

/**
 * 文件名 → 会话分组（纯逻辑）。
 *
 * 会话识别以 `sensor_<stamp>.csv` 为锚，`events_<stamp>.csv` 与 `<stamp>_meta.json` 同组；
 * **容错**：任一文件缺失也按现存文件成组；不属于会话的文件（如 `replay_report.txt`）被忽略。
 */
object CsvSessionScanner {

    const val REPLAY_REPORT = "replay_report.txt"

    /** `sensor_20260925_200159.csv` → `20260925_200159`；非会话文件返回 null */
    fun stampOf(fileName: String): String? = when {
        fileName == REPLAY_REPORT -> null
        fileName.startsWith("sensor_") && fileName.endsWith(".csv") ->
            fileName.removePrefix("sensor_").removeSuffix(".csv").ifEmpty { null }
        fileName.startsWith("events_") && fileName.endsWith(".csv") ->
            fileName.removePrefix("events_").removeSuffix(".csv").ifEmpty { null }
        fileName.endsWith("_meta.json") ->
            fileName.removeSuffix("_meta.json").ifEmpty { null }
        else -> null
    }

    /** 按 stamp 成组；返回顺序不保证，调用方无需依赖 */
    fun group(entries: List<LogFileEntry>): List<CsvSession> = entries
        .mapNotNull { e -> stampOf(e.name)?.let { it to e } }
        .groupBy({ it.first }, { it.second })
        .map { (stamp, group) ->
            CsvSession(
                stamp = stamp,
                totalBytes = group.sumOf { it.sizeBytes },
                lastModifiedMs = group.maxOf { it.lastModifiedMs },
            )
        }
}

/**
 * 会话级清理选择（纯函数，JVM 可测）。
 *
 * 三个规则**叠加**，都从最旧的删起：
 * 1. 年龄：早于 `now - maxDays` 的会话；
 * 2. 次数：保留最新 `maxSessions` 次（在通过年龄筛选的集合里）；
 * 3. 体积：保留者总字节数降到 `maxTotalBytes` 以内。
 *
 * 约束：
 * - [activeStamp]（正在录制的会话）**永不**删除，但**计入**次数与体积（它确实占着盘）；
 * - `replay_report.txt` 不属于任何会话，本函数天然不涉及；由调用方「只删会话三件套」保证不误删；
 * - 返回值按**最旧在前**（同名同刻时按 stamp 字典序），调用方按序删除即可。
 */
object CsvRetention {

    fun selectForDeletion(
        sessions: List<CsvSession>,
        nowMs: Long,
        policy: RetentionPolicy,
        activeStamp: String? = null,
    ): List<String> {
        // 去重 + 最新在前（时间戳并列时按 stamp 降序：yyyyMMdd_HHmmss 字典序 = 时间序）
        val newestFirst = sessions
            .distinctBy { it.stamp }
            .sortedWith(compareByDescending<CsvSession> { it.lastModifiedMs }.thenByDescending { it.stamp })

        val cutoffMs = nowMs - policy.maxDays.toLong() * 24L * 3600_000L

        // 规则 1（年龄）：过旧的直接淘汰；active 豁免
        val alive = ArrayList<CsvSession>(newestFirst.size)
        val doomed = LinkedHashSet<String>()
        for (s in newestFirst) {
            if (s.stamp != activeStamp && s.lastModifiedMs < cutoffMs) doomed += s.stamp else alive += s
        }

        // 规则 2（次数）：alive 已是最新在前，保留前 maxSessions 个（active 占名额，不删）
        val withinCount = ArrayList<CsvSession>(alive.size)
        alive.forEachIndexed { i, s ->
            if (i < policy.maxSessions || s.stamp == activeStamp) withinCount += s else doomed += s.stamp
        }

        // 规则 3（体积）：从最旧的保留者删起，直到总量达标（active 不参与删除）
        var total = withinCount.sumOf { it.totalBytes }
        if (total > policy.maxTotalBytes) {
            for (i in withinCount.indices.reversed()) { // 最旧优先
                if (total <= policy.maxTotalBytes) break
                val s = withinCount[i]
                if (s.stamp == activeStamp) continue
                doomed += s.stamp
                total -= s.totalBytes
            }
        }

        return doomed.sorted()
    }
}
