package com.metrostop.reminder

import com.metrostop.reminder.platform.lab.LabCellCollector
import com.metrostop.reminder.platform.lab.LabWifiCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹两流（cellular-wifi-fingerprint-validate）的纯函数部分：
 * BSSID SHA-1 截断哈希（隐私硬要求：原 MAC 不入库）/ cellid 行拼装与写行决策（变化即写 + 1 Hz 节流）。
 * 平台回调（扫描广播 / PhoneStateListener）不做 JVM 测试。
 */
class LabWifiCellCollectorsTest {

    // ---------------- LabWifiCollector.hashBssid ----------------

    @Test
    fun `哈希对齐SHA1标准向量`() {
        // FIPS 标准测试向量：SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals("a9993e3647", LabWifiCollector.hashBssid("abc"))
    }

    @Test
    fun `哈希大小写不敏感且定长小写hex`() {
        assertEquals(
            LabWifiCollector.hashBssid("AA:BB:CC:DD:EE:FF"),
            LabWifiCollector.hashBssid("aa:bb:cc:dd:ee:ff"),
        )
        val h = LabWifiCollector.hashBssid("aa:bb:cc:dd:ee:ff")
        assertEquals(10, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
    }

    @Test
    fun `不同BSSID哈希不同_同输入确定`() {
        val a = LabWifiCollector.hashBssid("aa:bb:cc:dd:ee:01")
        val b = LabWifiCollector.hashBssid("aa:bb:cc:dd:ee:02")
        assertNotEquals(a, b)
        assertEquals(a, LabWifiCollector.hashBssid("aa:bb:cc:dd:ee:01"))
    }

    // ---------------- LabWifiCollector 扫描节奏 / 事件节流（wifi-collector-fix 回归） ----------------

    @Test
    fun `扫描请求决策_30秒内不重复请求_满间隔放行`() {
        // 2026-09-29 早通勤事故回归：回调风暴下 30 s 内绝不重复请求
        assertFalse(
            LabWifiCollector.shouldRequestScan(30_000, lastRequestMs = 10_000, minIntervalMs = 30_000),
        )
        assertTrue(
            LabWifiCollector.shouldRequestScan(40_000, lastRequestMs = 10_000, minIntervalMs = 30_000),
        )
        // 恰好满间隔 → 放行
        assertTrue(
            LabWifiCollector.shouldRequestScan(40_000, lastRequestMs = 10_000, minIntervalMs = 30_000),
        )
        // 首次请求（lastRequest=0，运行时 elapsedRealtime 为开机起的大数值）必放行
        assertTrue(LabWifiCollector.shouldRequestScan(3_600_000, lastRequestMs = 0, minIntervalMs = 30_000))
    }

    @Test
    fun `失败事件节流_首次必写_节流窗内静默_满窗再写`() {
        val th = LabWifiCollector.FAIL_EVENT_THROTTLE_MS
        // 首次（lastEvent=0）必写
        assertTrue(LabWifiCollector.shouldWriteFailEvent(1_000, lastEventMs = 0, throttleMs = th))
        // 窗内静默
        assertFalse(LabWifiCollector.shouldWriteFailEvent(30_000, lastEventMs = 1_000, throttleMs = th))
        // 满窗再写
        assertTrue(LabWifiCollector.shouldWriteFailEvent(61_000, lastEventMs = 1_000, throttleMs = th))
    }

    // ---------------- LabCellCollector 纯函数 ----------------

    private fun snap(pci: Int, ci: Long, rssi: Int, registered: Boolean = false) =
        LabCellCollector.CellSnapshot(pci, ci, rssi, registered)

    @Test
    fun `cells行拼装_多值竖线分隔`() {
        assertEquals("5:12345:-85", LabCellCollector.formatCells(listOf(snap(5, 12345, -85))))
        assertEquals(
            "5:12345:-85|-1:999:-95",
            LabCellCollector.formatCells(listOf(snap(5, 12345, -85), snap(-1, 999, -95))),
        )
        assertEquals("", LabCellCollector.formatCells(emptyList()))
    }

    @Test
    fun `主服务小区排首_同状态保持原序`() {
        val input = listOf(
            snap(1, 11, -90, registered = false),
            snap(2, 22, -80, registered = true),
            snap(3, 33, -85, registered = false),
        )
        val sorted = LabCellCollector.sortedCells(input)
        assertEquals(listOf(2, 1, 3), sorted.map { it.pci })
    }

    @Test
    fun `写行决策_变化即写_否则1Hz节流`() {
        // 小区切换：距上次 <1s 也必须立即写（切换不落节流窗）
        assertTrue(
            LabCellCollector.shouldWrite(
                nowMs = 10_500, lastWriteMs = 10_000,
                signature = "5:100:-85", lastSignature = "5:200:-85",
            ),
        )
        // 签名不变 + 窗口内 → 不写
        assertFalse(
            LabCellCollector.shouldWrite(
                nowMs = 10_500, lastWriteMs = 10_000,
                signature = "5:100:-85", lastSignature = "5:100:-85",
            ),
        )
        // 签名不变 + 满 1 s → 写
        assertTrue(
            LabCellCollector.shouldWrite(
                nowMs = 11_000, lastWriteMs = 10_000,
                signature = "5:100:-85", lastSignature = "5:100:-85",
            ),
        )
    }
}
