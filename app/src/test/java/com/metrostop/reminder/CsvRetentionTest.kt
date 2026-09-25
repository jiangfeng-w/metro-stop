package com.metrostop.reminder

import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.retention.CsvRetention
import com.metrostop.reminder.core.retention.CsvSession
import com.metrostop.reminder.core.retention.CsvSessionScanner
import com.metrostop.reminder.core.retention.LogFileEntry
import com.metrostop.reminder.core.retention.RetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `csv-storage-policy`：CSV 保留策略纯逻辑（超次数 / 超天数 / 超体积 / 当前会话保护 / 分组成对）。
 *
 * 硬性规则 2 的精神：保留数值只在 `TuningConfig`，本测试直接断言默认值与派生策略一致。
 */
class CsvRetentionTest {

    private val day = 24L * 3600_000L
    private val now = 1_800_000_000_000L // 固定 now，避免测试随时钟漂移

    private fun policy(
        sessions: Int = 10,
        days: Int = 14,
        mb: Int = 200,
    ) = RetentionPolicy(
        maxSessions = sessions,
        maxDays = days,
        maxTotalBytes = mb.toLong() * 1024 * 1024,
    )

    /** 造 n 个会话：第 0 个最新（stamp 最大） */
    private fun sessions(n: Int, ageDaysEach: Long = 0L, bytesEach: Long = 1000L): List<CsvSession> =
        (0 until n).map { i ->
            CsvSession(
                stamp = "202609%02d_120000".format(25 - i),
                totalBytes = bytesEach,
                lastModifiedMs = now - i * ageDaysEach * day,
            )
        }

    // ---------------------------------------------------------------- 分组成对

    @Test
    fun `文件名识别_三件套同组_非会话文件忽略`() {
        assertEquals("20260925_200159", CsvSessionScanner.stampOf("sensor_20260925_200159.csv"))
        assertEquals("20260925_200159", CsvSessionScanner.stampOf("events_20260925_200159.csv"))
        assertEquals("20260925_200159", CsvSessionScanner.stampOf("20260925_200159_meta.json"))
        assertNull("replay_report.txt 不是会话", CsvSessionScanner.stampOf("replay_report.txt"))
        assertNull("无关文件应忽略", CsvSessionScanner.stampOf("random.txt"))
    }

    @Test
    fun `部分文件缺失也能成组_体积按现存文件算`() {
        val entries = listOf(
            LogFileEntry("sensor_a.csv", 100, now),
            LogFileEntry("sensor_b.csv", 200, now),
            LogFileEntry("b_meta.json", 50, now), // b 缺 events，仍算一组
        )
        val groups = CsvSessionScanner.group(entries)
        assertEquals("应识别 2 个会话", 2, groups.size)
        val b = groups.first { it.stamp == "b" }
        assertEquals("缺文件时体积只算现存", 250L, b.totalBytes)
    }

    // ---------------------------------------------------------------- 超次数

    @Test
    fun `超次数_从最旧删起_只留最近N次`() {
        val doomed = CsvRetention.selectForDeletion(sessions(15), now, policy(sessions = 10))
        assertEquals("15 次会话应删 5 次", 5, doomed.size)
        // stamp 字典序：20260911_120000 最早 → 从最旧删
        assertEquals("最旧的 5 个待删", listOf(
            "20260911_120000", "20260912_120000", "20260913_120000",
            "20260914_120000", "20260915_120000",
        ), doomed)
    }

    @Test
    fun `不超次数则不删`() {
        assertTrue(CsvRetention.selectForDeletion(sessions(10), now, policy(sessions = 10)).isEmpty())
        assertTrue(CsvRetention.selectForDeletion(sessions(3), now, policy(sessions = 10)).isEmpty())
    }

    // ---------------------------------------------------------------- 超天数

    @Test
    fun `超天数_14天前的会话待删`() {
        // 5 个会话，每个相隔 5 天 → 第 0~2 天内(0/5/10 天)，第 3 个 15 天、第 4 个 20 天
        val doomed = CsvRetention.selectForDeletion(sessions(5, ageDaysEach = 5), now, policy(days = 14))
        assertEquals("15/20 天前的两个会话待删", 2, doomed.size)
        assertEquals("返回顺序应为最旧在前", listOf("20260921_120000", "20260922_120000"), doomed)
    }

    @Test
    fun `正好14天边界_不算超龄`() {
        val exactly = listOf(CsvSession("20260911_120000", 1000, now - 14 * day))
        assertTrue(
            "恰好 14 天应保留（判据是 < cutoff）",
            CsvRetention.selectForDeletion(exactly, now, policy(days = 14)).isEmpty(),
        )
    }

    // ---------------------------------------------------------------- 超体积

    @Test
    fun `超体积_从最旧保留者删起直到达标`() {
        // 5 个会话各 30 MB，上限 100 MB → 需删 2 个（剩 90 MB）
        val doomed = CsvRetention.selectForDeletion(
            sessions(5, bytesEach = 30L * 1024 * 1024),
            now,
            policy(mb = 100),
        )
        assertEquals("应删最旧 2 个", 2, doomed.size)
        assertEquals(listOf("20260921_120000", "20260922_120000"), doomed.sorted())
    }

    @Test
    fun `体积上限内不删`() {
        val doomed = CsvRetention.selectForDeletion(
            sessions(5, bytesEach = 19L * 1024 * 1024), // 95 MB < 100 MB
            now,
            policy(mb = 100),
        )
        assertTrue("95 MB 未超 100 MB 上限", doomed.isEmpty())
    }

    // ---------------------------------------------------------------- active 保护

    @Test
    fun `当前会话永不删除_且计入次数与体积`() {
        val all = sessions(15)
        val active = all.first().stamp // 最新那个正在录
        val doomed = CsvRetention.selectForDeletion(all, now, policy(sessions = 10), activeStamp = active)
        assertFalse("正在录制的会话不得出现在删除列表", doomed.contains(active))
        assertEquals("active 占 1 个名额 → 仍需删 5 个", 5, doomed.size)
    }

    @Test
    fun `当前会话超龄也保留`() {
        val old = CsvSession("20260101_120000", 1000, now - 300 * day)
        val doomed = CsvRetention.selectForDeletion(listOf(old), now, policy(days = 14), activeStamp = old.stamp)
        assertTrue("active 即使超龄也豁免", doomed.isEmpty())
    }

    @Test
    fun `当前会话体积超限_不删它_删其余旧的`() {
        val active = CsvSession("20260925_120000", 150L * 1024 * 1024, now) // 自己就 150 MB
        val others = listOf(
            CsvSession("20260924_120000", 20L * 1024 * 1024, now - day),
            CsvSession("20260923_120000", 20L * 1024 * 1024, now - 2 * day),
        )
        val doomed = CsvRetention.selectForDeletion(
            others + active, now, policy(mb = 100), activeStamp = active.stamp,
        )
        assertFalse("active 绝不删", doomed.contains(active.stamp))
        assertEquals("删旧的直到 ≤100 MB", 2, doomed.size)
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `空目录不崩且返回空`() {
        assertTrue(CsvRetention.selectForDeletion(emptyList(), now, policy()).isEmpty())
    }

    @Test
    fun `重复 stamp 去重`() {
        val dup = listOf(
            CsvSession("20260925_120000", 1000, now),
            CsvSession("20260925_120000", 1000, now),
        )
        assertTrue(CsvRetention.selectForDeletion(dup, now, policy()).isEmpty())
    }

    // ---------------------------------------------------------------- 与 TuningConfig 对齐

    @Test
    fun `默认策略与TuningConfig一致`() {
        val p = RetentionPolicy.from(TuningConfig.Default)
        assertEquals("默认保留最近 10 次", 10, p.maxSessions)
        assertEquals("默认保留 14 天", 14, p.maxDays)
        assertEquals("默认上限 200 MB", 200L * 1024 * 1024, p.maxTotalBytes)
    }

    /** 真实场景量级：一次通勤 19 MB × 每天 2 次 → 200 MB 上限会在 5 天左右先于次数触发 */
    @Test
    fun `真实量级_每天两次通勤`() {
        val perTrip = 19L * 1024 * 1024
        // 12 次（6 天）各 19 MB = 228 MB > 200 MB
        val all = (0 until 12).map { i ->
            CsvSession("202609%02d_080000".format(25 - i), perTrip, now - i * 12 * 3600_000L)
        }
        val doomed = CsvRetention.selectForDeletion(all, now, RetentionPolicy.from(TuningConfig.Default))
        assertEquals("228 MB 超 200 MB → 删 2 个最旧的", 2, doomed.size)
        assertTrue("删除后应 ≤200 MB", (all.size - doomed.size) * perTrip <= 200L * 1024 * 1024)
    }
}
