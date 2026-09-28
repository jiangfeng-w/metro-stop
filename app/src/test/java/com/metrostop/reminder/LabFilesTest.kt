package com.metrostop.reminder

import com.metrostop.reminder.core.lab.LabFiles
import com.metrostop.reminder.core.lab.LabRetention
import com.metrostop.reminder.core.lab.LabRetentionPolicy
import com.metrostop.reminder.core.lab.LabSession
import com.metrostop.reminder.core.retention.CsvSessionScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实验室采集（lab-data-collection）文件约定与保留策略（纯逻辑，JVM 可测）。
 *
 * 关键回归点：lab 目录 / 文件与监测 CSV 三件套（`CsvSessionScanner`）**互不误认**——
 * 监测保留策略不得碰 lab 数据，lab 保留策略不得碰监测 CSV。
 */
class LabFilesTest {

    private val nowMs = 1_791_000_000_000L
    private val dayMs = 24L * 3600_000L

    // ---------------- 目录 / 流名识别 ----------------

    @Test
    fun `目录名识别`() {
        assertEquals("20260928_073000", LabFiles.stampOfDir("lab_20260928_073000"))
        assertNull(LabFiles.stampOfDir("lab_"))            // 前缀后为空
        assertNull(LabFiles.stampOfDir("sensor_20260928_073000.csv"))
        assertNull(LabFiles.stampOfDir("20260928_073000_meta.json"))
        assertNull(LabFiles.stampOfDir("replay_report.txt"))
    }

    @Test
    fun `流文件名识别`() {
        assertEquals("imu", LabFiles.streamOf("lab_imu.csv"))
        assertEquals("events", LabFiles.streamOf("lab_events.csv"))
        assertNull(LabFiles.streamOf("lab_meta.json"))     // meta 不是流
        assertNull(LabFiles.streamOf("lab_.csv"))          // 流名为空
        assertNull(LabFiles.streamOf("sensor_20260928_073000.csv"))
        assertNull(LabFiles.streamOf("events_20260928_073000.csv"))
        assertNull(LabFiles.streamOf("20260928_073000_meta.json"))
    }

    @Test
    fun `与监测会话扫描器互不误认`() {
        // 监测扫描器：lab 文件一律返回 null（不会被当成监测会话、不会被监测保留策略删除）
        assertNull(CsvSessionScanner.stampOf("lab_imu.csv"))
        assertNull(CsvSessionScanner.stampOf("lab_20260928_073000"))
        // meta.json 特意不叫 lab_meta.json：`*_meta.json` 是监测三件套模式，重名会被误判成 stamp="lab"
        assertNull(CsvSessionScanner.stampOf("meta.json"))
        assertNull(LabFiles.streamOf("meta.json"))
        // 反向：lab 识别器不认监测三件套
        assertNull(LabFiles.stampOfDir("sensor_20260926_142910.csv"))
        assertNull(LabFiles.streamOf("20260926_142910_meta.json"))
    }

    @Test
    fun `流清单齐全`() {
        // lab-data-collection 第五节 8 类流 + cellular-wifi-fingerprint-validate 新增 wifi/cellid 两流
        assertEquals(
            listOf("imu", "baro", "light", "loc", "gnss", "steps", "cell", "wifi", "cellid", "events"),
            LabFiles.STREAMS,
        )
    }

    // ---------------- 保留策略 ----------------

    private fun session(stamp: String, ageDays: Double, bytes: Long = 1000) = LabSession(
        stamp = stamp,
        totalBytes = bytes,
        lastModifiedMs = nowMs - (ageDays * dayMs).toLong(),
    )

    @Test
    fun `保留最新3次_其余删除`() {
        // stamp 字典序 = 时间序（yyyyMMdd_HHmmss）；ageDays 越大越旧
        val sessions = listOf(
            session("20260923_080000", ageDays = 4.0),
            session("20260922_080000", ageDays = 3.0),
            session("20260921_080000", ageDays = 2.0),
            session("20260920_080000", ageDays = 1.0),
        )
        val doomed = LabRetention.selectForDeletion(sessions, nowMs, LabRetentionPolicy(maxSessions = 3, maxDays = 7))
        // 最旧的 20260923 被删，其余 3 个保留
        assertEquals(listOf("20260923_080000"), doomed)
    }

    @Test
    fun `超7天删除_含7天边界`() {
        val sessions = listOf(
            session("a", ageDays = 6.9),
            session("b", ageDays = 7.0),   // 恰好 7 天：不删（边界含在保留侧）
            session("c", ageDays = 7.1),   // 超 7 天：删
        )
        val doomed = LabRetention.selectForDeletion(sessions, nowMs, LabRetentionPolicy(maxSessions = 10, maxDays = 7))
        assertEquals(listOf("c"), doomed)
    }

    @Test
    fun `正在采集的会话永不删但占名额`() {
        val sessions = listOf(
            session("active", ageDays = 0.0),
            session("s1", ageDays = 1.0),
            session("s2", ageDays = 2.0),
            session("s3", ageDays = 3.0),
        )
        val doomed = LabRetention.selectForDeletion(sessions, nowMs, LabRetentionPolicy(maxSessions = 2, maxDays = 7), activeStamp = "active")
        // active 占一个名额 → 只再保留最新 1 个，其余删
        assertEquals(listOf("s2", "s3"), doomed)
    }

    @Test
    fun `active会话过期也不删`() {
        val sessions = listOf(session("active", ageDays = 30.0))
        val doomed = LabRetention.selectForDeletion(sessions, nowMs, LabRetentionPolicy(maxSessions = 3, maxDays = 7), activeStamp = "active")
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `空列表与重复stamp容错`() {
        assertTrue(LabRetention.selectForDeletion(emptyList(), nowMs).isEmpty())
        val sessions = listOf(session("x", ageDays = 1.0), session("x", ageDays = 1.0))
        val doomed = LabRetention.selectForDeletion(sessions, nowMs, LabRetentionPolicy(maxSessions = 3, maxDays = 7))
        assertTrue(doomed.isEmpty()) // 去重后只剩 1 个，不删
    }

    @Test
    fun `默认策略对齐需求文档`() {
        // 需求文档：最多 3 次 / 7 天
        assertEquals(3, LabRetentionPolicy.Default.maxSessions)
        assertEquals(7, LabRetentionPolicy.Default.maxDays)
        assertFalse(LabFiles.STREAMS.isEmpty())
    }

    // ---------------- 场景清单 ----------------

    @Test
    fun `场景id唯一且非空`() {
        val ids = com.metrostop.reminder.core.lab.LabScenarios.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `场景分组覆盖全部必需场景`() {
        val s = com.metrostop.reminder.core.lab.LabScenarios
        val ids = s.ALL.map { it.id }.toSet()
        // 需求第四节场景清单的关键项
        val required = setOf(
            "enter_walk_outdoor", "enter_run", "enter_stairs",       // 用户提出
            "enter_walk_indoor", "enter_ride_sit_phone",             // 用户提出
            "enter_platform_wait", "enter_escalator", "enter_ride_stand", // 补充：静立/扶梯/站姿
            "enter_ride_walk", "enter_transfer",                     // 补充：车厢走动/换乘
        )
        assertTrue("缺少场景: ${required - ids}", ids.containsAll(required))
        // 兜底标记 id 不与场景冲突
        assertFalse(ids.contains(s.GENERIC_MARK))
        assertEquals(s.ALL.size, s.byId.size)
    }
}
