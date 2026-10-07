package com.metrostop.reminder.platform.lab

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * lab 采集的蜂窝小区序列流（cellular-wifi-fingerprint-validate，**旁路工具，与监测主链路零交集**）：
 *
 * - 数据源：[TelephonyManager.listen] `LISTEN_CELL_INFO` 回调 + 启动时 [TelephonyManager.getAllCellInfo]
 *   首拍；**1 Hz 节流 + 变化即写**——小区集合签名变化立即落行（切换不落节流窗），否则 1 Hz 快照（需求 3.1）；
 * - 行格式：`t_ms,utc_ms,cells`，cells = `pci:ci:rssi:ta:rat` 多值 `|` 分隔、**主服务（registered）排首**
 *   （批次 B5 起 5 段：ta = LTE TA 格原始值 / NR 微秒原始值（两制式单位不同，分析按 rat 分列换算
 *   距离：LTE ≈78 m/格、NR ≈150 m/μs），未知 −1；rat ∈ lte|nr|wcdma|tdscdma|gsm|cdma。
 *   前 3 段与主链路 `cell_<stamp>.csv` 的 3 段格式兼容——主链路走 [formatCells]（rssi 3 段，冻结不动），
 *   lab 走 [formatCellsLab]；签名 [cellsSignature] 不含 TA，节拍行为不变）；
 * - 制式差异归一为 [CellSnapshot]（LTE/NR 取 pci/ci，WCDMA/TDSCDMA 取 psc/cid，GSM/CDMA 无 pci 填 −1），
 *   rssi 列取该小区 `dbm`（与既有 `cell` 流口径一致）；
 * - 隐私（需求 3.3）：CI/PCI 是运营商网络标识，可直接落盘；
 * - 权限：`READ_PHONE_STATE`（lab 权限矩阵 hasPhone，与既有 `cell` 流同门）。
 */
class LabCellCollector(
    context: Context,
    private val write: (stream: String, row: String) -> Unit,
    private val writeEvent: (type: String, detail: String) -> Unit,
) {

    private val appContext = context.applicationContext
    private var telephony: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    private var lastRowElapsedMs = 0L
    private var lastSignature: String? = null

    /** 注册 CellInfo 监听并落首拍；失败返回 false（调用方从 activeStreams 移除 cellid） */
    @Suppress("DEPRECATION")
    fun start(): Boolean {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: run {
            writeEvent("CELLID_START", "no_telephony_service")
            return false
        }
        telephony = tm
        val l = object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                onCells(cellInfo ?: emptyList())
            }
        }
        listener = l
        val ok = runCatching { tm.listen(l, PhoneStateListener.LISTEN_CELL_INFO) }.isSuccess
        if (!ok) {
            writeEvent("CELLID_START", "listen_failed")
            listener = null
            telephony = null
            return false
        }
        writeEvent("CELLID_START", "cell_info_listener")
        onCells(runCatching { tm.allCellInfo }.getOrNull().orEmpty())
        return true
    }

    fun stop() {
        val tm = telephony
        val l = listener
        if (tm != null && l != null) {
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        telephony = null
        listener = null
    }

    private fun onCells(cells: List<CellInfo>) {
        val snaps = sortedCells(parseCells(cells))
        val signature = cellsSignature(snaps)
        val now = SystemClock.elapsedRealtime()
        // 变化即写（切换不落节流窗）；签名不变才走 1 Hz 节流
        if (!shouldWrite(now, lastRowElapsedMs, signature, lastSignature)) return
        lastSignature = signature
        lastRowElapsedMs = now
        write("cellid", "$now,${System.currentTimeMillis()},${formatCellsLab(snaps)}")
    }

    /** 单个小区的归一化快照（未知值一律 −1）；ta/rat 批次 B5 新增，带默认值——主链路复用处零改动 */
    data class CellSnapshot(
        val pci: Int,
        val ci: Long,
        val rssi: Int,
        val registered: Boolean,
        val ta: Int = UNKNOWN,
        val rat: String = "",
    )

    companion object {
        private const val UNKNOWN = -1
        private const val RAT_LTE = "lte"
        private const val RAT_NR = "nr"
        private const val RAT_WCDMA = "wcdma"
        private const val RAT_TDSCDMA = "tdscdma"
        private const val RAT_GSM = "gsm"
        private const val RAT_CDMA = "cdma"

        /** 写行决策（纯函数可测）：签名变化 → 立即写；否则距上次 ≥ 1 s 才写 */
        fun shouldWrite(
            nowMs: Long,
            lastWriteMs: Long,
            signature: String,
            lastSignature: String?,
            minIntervalMs: Long = 1000L,
        ): Boolean = signature != lastSignature || nowMs - lastWriteMs >= minIntervalMs

        /** 主服务小区排首（稳定排序：同 registered 状态保持系统给的顺序） */
        fun sortedCells(snaps: List<CellSnapshot>): List<CellSnapshot> =
            snaps.sortedByDescending { it.registered }

        /** cells 列拼装：`pci:ci:rssi` 多值 `|` 分隔——**主链路 `cell_<stamp>.csv` 走此函数，3 段格式冻结** */
        fun formatCells(snaps: List<CellSnapshot>): String =
            snaps.joinToString("|") { "${it.pci}:${it.ci}:${it.rssi}" }

        /**
         * lab cellid 流行拼装（批次 B5）：`pci:ci:rssi:ta:rat`——前 3 段与 [formatCells] 完全兼容；
         * ta = LTE TA 格原始值 / NR 微秒原始值（**两制式单位不同**，分析按 rat 换算距离）；
         * 签名（[cellsSignature]）不含 TA，避免基带高频回报 TA 放大写行量。
         */
        fun formatCellsLab(snaps: List<CellSnapshot>): String =
            snaps.joinToString("|") { "${it.pci}:${it.ci}:${it.rssi}:${it.ta}:${it.rat}" }

        fun cellsSignature(snaps: List<CellSnapshot>): String = formatCells(snaps)

        /** 各制式 CellInfo → 快照；无法识别的制式返回 null（落事件由调用方兜底） */
        fun parseCells(cells: List<CellInfo>): List<CellSnapshot> = cells.mapNotNull { info ->
            val dbm = runCatching { info.cellSignalStrength.dbm }
                .getOrNull()
                ?.takeUnless { it == Int.MAX_VALUE }
                ?: UNKNOWN
            val snap = when (info) {
                is CellInfoLte -> CellSnapshot(
                    idOrUnknown(info.cellIdentity.pci), ciOrUnknown(info.cellIdentity.ci.toLong()),
                    dbm, info.isRegistered,
                    ta = taOrUnknown(runCatching { info.cellSignalStrength.timingAdvance }.getOrDefault(UNKNOWN)),
                    rat = RAT_LTE,
                )
                is CellInfoWcdma -> CellSnapshot(
                    idOrUnknown(info.cellIdentity.psc), ciOrUnknown(info.cellIdentity.cid.toLong()),
                    dbm, info.isRegistered, rat = RAT_WCDMA,
                )
                is CellInfoTdscdma -> CellSnapshot(
                    idOrUnknown(info.cellIdentity.cpid), ciOrUnknown(info.cellIdentity.cid.toLong()),
                    dbm, info.isRegistered, rat = RAT_TDSCDMA,
                )
                is CellInfoGsm -> CellSnapshot(
                    UNKNOWN, ciOrUnknown(info.cellIdentity.cid.toLong()), dbm, info.isRegistered, rat = RAT_GSM,
                )
                is CellInfoCdma -> CellSnapshot(
                    UNKNOWN, ciOrUnknown(info.cellIdentity.basestationId.toLong()), dbm, info.isRegistered, rat = RAT_CDMA,
                )
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) parseNr(info, dbm) else null
            }
            snap
        }

        private fun parseNr(info: CellInfo, dbm: Int): CellSnapshot? {
            val nr = info as? CellInfoNr ?: return null
            val id = nr.cellIdentity as? CellIdentityNr ?: return null
            // NR TA（微秒制）API 34 起才有；runCatching 兜 HyperOS 基带异常，哨兵归一兜「恒不回报」。
            // CellInfoNr.getCellSignalStrength() 返回基类（不协变覆写），须显式转型取 Nr 子类
            val ta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                taOrUnknown(
                    runCatching {
                        (nr.cellSignalStrength as? CellSignalStrengthNr)?.timingAdvanceMicros ?: UNKNOWN
                    }.getOrDefault(UNKNOWN),
                )
            } else {
                UNKNOWN
            }
            return CellSnapshot(
                idOrUnknown(id.pci), ciOrUnknown(id.nci), dbm, nr.isRegistered, ta = ta, rat = RAT_NR,
            )
        }

        private fun idOrUnknown(v: Int): Int = if (v == Int.MAX_VALUE) UNKNOWN else v

        /**
         * ta 未知哨兵归一（纯函数可测，批次 B5）：LTE `getTimingAdvance()` / NR `getTimingAdvanceMicros()`
         * 的未知值都是 `CellInfo.UNAVAILABLE`（`Int.MAX_VALUE`）；负值按异常同归 UNKNOWN
         * （LTE TA 合法域含 0，不可用「>0 才有效」判断）。
         */
        internal fun taOrUnknown(v: Int): Int = if (v == Int.MAX_VALUE || v < 0) UNKNOWN else v

        /**
         * ci 未知哨兵归一（纯函数可测）：各制式未知值是 `Int.MAX_VALUE`（LTE/WCDMA/TDSCDMA/GSM/CDMA
         * 的 `getCi()`）或 `Long.MAX_VALUE`（NR 的 `getNci()`）——只滤后者会把伪小区
         * `…:2147483647` 落盘（2026-09-30 晚通勤实测出现 `184:2147483647`，多趟分析发现 2）。
         */
        internal fun ciOrUnknown(v: Long): Long =
            if (v == Long.MAX_VALUE || v == Int.MAX_VALUE.toLong()) UNKNOWN.toLong() else v
    }
}
