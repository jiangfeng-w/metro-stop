package com.metrostop.reminder

import com.metrostop.reminder.platform.lab.CellInfoStreamEngine
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
                signature = "5:100r", lastSignature = "5:200",
            ),
        )
        // 签名不变 + 窗口内 → 不写
        assertFalse(
            LabCellCollector.shouldWrite(
                nowMs = 10_500, lastWriteMs = 10_000,
                signature = "5:100r", lastSignature = "5:100r",
            ),
        )
        // 签名不变 + 满 1 s → 写
        assertTrue(
            LabCellCollector.shouldWrite(
                nowMs = 11_000, lastWriteMs = 10_000,
                signature = "5:100r", lastSignature = "5:100r",
            ),
        )
    }

    // ---------------- C1 拍点加密：身份签名拆分 + 静默活性检测 ----------------

    @Test
    fun `身份签名_不含rssi与TA_主服务带r标记`() {
        // P0-3（C1）：写行决策只认身份签名 = pci:ci + 主服务标记（r），rssi / TA / 制式都不参与
        assertEquals(
            "5:100r",
            LabCellCollector.identitySignature(
                listOf(LabCellCollector.CellSnapshot(5, 100, -85, true, ta = 31, rat = "lte")),
            ),
        )
        assertEquals(
            "5:100r|7:200",
            LabCellCollector.identitySignature(
                listOf(snap(5, 100, -85, registered = true), snap(7, 200, -90)),
            ),
        )
        assertEquals("", LabCellCollector.identitySignature(emptyList()))
    }

    @Test
    fun `写行决策_身份不变仅rssi抖动_不破1Hz节流`() {
        // 2026-10-08 晚实测：rssi 抖动（−85 → −71）在旧签名（含 rssi）下会「变化即写」，
        // 断档指标被写成「信号抖没抖」；签名拆分后身份不变 → 走 1 Hz 节流（断档 = 有没有拍到）
        val a = listOf(snap(5, 100, -85, registered = true))
        val b = listOf(snap(5, 100, -71, registered = true))
        assertEquals(LabCellCollector.identitySignature(a), LabCellCollector.identitySignature(b))
        assertNotEquals( // 全量签名（含 rssi）仍能区分——保留给离线审计
            LabCellCollector.cellsSignature(a),
            LabCellCollector.cellsSignature(b),
        )
        assertFalse(
            LabCellCollector.shouldWrite(
                nowMs = 10_500, lastWriteMs = 10_000,
                signature = LabCellCollector.identitySignature(b),
                lastSignature = LabCellCollector.identitySignature(a),
            ),
        )
    }

    @Test
    fun `静默告警_首拍前不报_过窗报一次_已报不重复`() {
        val warn = 45_000L
        // 首拍前（尚无样本）不报——避免启动瞬间误报
        assertFalse(CellInfoStreamEngine.silenceDue(100_000, lastSampleMs = 0, warned = false, warnMs = warn))
        // 有样本但未过窗 → 不报
        assertFalse(CellInfoStreamEngine.silenceDue(100_000, lastSampleMs = 60_000, warned = false, warnMs = warn))
        // 刚好过窗 → 报（10-08 晚塌缩 1 拍/27~49 s 场景下 45 s 窗口必然触发）
        assertTrue(CellInfoStreamEngine.silenceDue(105_000, lastSampleMs = 60_000, warned = false, warnMs = warn))
        // 本静默期已报过 → 不重复
        assertFalse(CellInfoStreamEngine.silenceDue(200_000, lastSampleMs = 60_000, warned = true, warnMs = warn))
    }

    @Test
    fun `ci未知哨兵两种极值都归UNKNOWN_伪小区不入库`() {
        // 2026-09-30 批次 A5 回归（多趟分析发现 2）：LTE `getCi()` 的未知值是 Int.MAX_VALUE，
        // 漏滤会落盘伪小区 `184:2147483647`（09-30 晚通勤实测出现）；NR `getNci()` 是 Long.MAX_VALUE
        assertEquals(-1L, LabCellCollector.ciOrUnknown(Int.MAX_VALUE.toLong()))
        assertEquals(-1L, LabCellCollector.ciOrUnknown(Long.MAX_VALUE))
        assertEquals(38656577578L, LabCellCollector.ciOrUnknown(38656577578L))
    }

    // ---------------- LabCellCollector TA 列（批次 B5） ----------------

    @Test
    fun `ta未知哨兵归一_极值与负值归UNKNOWN_零是合法TA`() {
        // LTE getTimingAdvance() / NR getTimingAdvanceMicros() 的未知值 = CellInfo.UNAVAILABLE(Int.MAX_VALUE)
        assertEquals(-1, LabCellCollector.taOrUnknown(Int.MAX_VALUE))
        // 负值按基带异常同归 UNKNOWN；LTE TA 合法域含 0（不可用 >0 判断有效性）
        assertEquals(-1, LabCellCollector.taOrUnknown(-3))
        assertEquals(0, LabCellCollector.taOrUnknown(0))
        assertEquals(31, LabCellCollector.taOrUnknown(31))
        assertEquals(1_234, LabCellCollector.taOrUnknown(1_234))
    }

    @Test
    fun `lab行拼装5段五元组_多值竖线分隔`() {
        assertEquals(
            "5:12345:-85:31:lte",
            LabCellCollector.formatCellsLab(listOf(LabCellCollector.CellSnapshot(5, 12345, -85, true, ta = 31, rat = "lte"))),
        )
        // NR 微秒制 + 未采集到 TA（−1）与邻区（默认 ta=−1）混排
        assertEquals(
            "42:38682632234:-78:-1:nr|-1:999:-95:-1:",
            LabCellCollector.formatCellsLab(
                listOf(
                    LabCellCollector.CellSnapshot(42, 38682632234L, -78, true, rat = "nr"),
                    LabCellCollector.CellSnapshot(-1, 999, -95, false),
                ),
            ),
        )
        assertEquals("", LabCellCollector.formatCellsLab(emptyList()))
    }

    @Test
    fun `formatCells保持3段_主链路cell流格式冻结`() {
        // CellMonitorCollector 复用此函数写 cell_<stamp>.csv（core 解析端 + 回放资产锁定的 pci:ci:rssi）；
        // CellSnapshot 新增 ta/rat 字段不得渗入输出
        val withTa = LabCellCollector.CellSnapshot(5, 12345, -85, true, ta = 31, rat = "lte")
        assertEquals("5:12345:-85", LabCellCollector.formatCells(listOf(withTa)))
        // 签名（节拍行为）同样不含 TA
        assertEquals("5:12345:-85", LabCellCollector.cellsSignature(listOf(withTa)))
    }
}
