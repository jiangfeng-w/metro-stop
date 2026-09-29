package com.metrostop.reminder.core.cell

import com.metrostop.reminder.core.model.TuningConfig

/**
 * 蜂窝站区跟踪器（v4，纯 Kotlin）：小区序列 → 「当前在哪个站区」。
 *
 * 判据（需求 2.2）：
 * - 主服务小区命中映射（主服务不中再试邻区）→ 候选站区；**连续 ≥[TuningConfig.cellZoneConfirmSamples] 拍
 *   同一候选 → 确认进区**（防单拍抖动 / 邻区列表瞬闪）；
 * - 当前小区不命中任何站区 → 立即退区（1 Hz 粒度下退区延迟 ≤1 拍，影响可忽略）；
 * - 站区是**位置区间**（同一 pci:ci 可覆盖相邻多站，实测 4 号线 701 区覆盖两站）。
 *
 * 全因果：只依赖输入样本序列；回放与现场喂同样的序列即得同样的状态（硬性规则 1）。
 */
class CellZoneTracker(
    private val lineMap: CellZoneLine?,
    private val config: TuningConfig,
) {

    /** 是否有效（映射存在且非空） */
    val active: Boolean get() = lineMap != null && lineMap.stations.isNotEmpty()

    /** 当前确认的站区位置区间（1 基路线计数）；不在任何站区为 null */
    var confirmedRange: IntRange? = null
        private set

    /** 确认时刻（最近一次进区） */
    var confirmedAtMs: Long = 0L
        private set

    private var candidate: IntRange? = null
    private var candidateHits = 0

    /** 站区显示名：单站返回站名，多站（共享小区段）返回「A~B」 */
    fun displayName(range: IntRange): String? {
        val stations = lineMap?.stations ?: return null
        val names = range.mapNotNull { p ->
            stations.getOrNull(p - 1)?.stationId?.substringAfterLast('_')
        }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> stationNameOf(range.first)
            else -> "${stationNameOf(range.first)}~${stationNameOf(range.last)}"
        }
    }

    /** position（1 基）→ 站名（来自映射表序，与路线切片校验一致） */
    fun stationNameAt(position: Int): String? =
        lineMap?.stations?.getOrNull(position - 1)?.stationId

    /**
     * 喂一条小区样本（1 Hz 节流 + 变化即写后的序列即可）。
     * @param mainCell 主服务小区标识 `pci:ci`（空串 / null = 无服务）
     * @param neighbors 邻区标识列表（主服务不中时兜底匹配）
     * @return 本次产生的进区 / 退区转移（供会话层发事件与副作用）
     */
    fun onCell(tMs: Long, mainCell: String?, neighbors: List<String>): List<ZoneTransition> {
        if (!active) return emptyList()
        val zone = resolve(mainCell, neighbors)
        return if (zone != null) {
            if (zone == candidate) {
                candidateHits++
            } else {
                candidate = zone
                candidateHits = 1
            }
            if (candidateHits >= config.cellZoneConfirmSamples && confirmedRange != zone) {
                confirmedRange = zone
                confirmedAtMs = tMs
                listOf(ZoneTransition.Entered(tMs, zone))
            } else {
                emptyList()
            }
        } else {
            candidate = null
            candidateHits = 0
            val prev = confirmedRange
            confirmedRange = null
            if (prev != null) listOf(ZoneTransition.Exited(tMs)) else emptyList()
        }
    }

    /** 主服务优先，其次邻区；命中 = 小区标识出现在某站的 cells 列表 */
    private fun resolve(mainCell: String?, neighbors: List<String>): IntRange? {
        val stations = lineMap?.stations ?: return null
        val byCell = { cell: String? ->
            if (cell.isNullOrBlank()) null else zoneOf(stations, cell)
        }
        return byCell(mainCell) ?: neighbors.firstNotNullOfOrNull { byCell(it) }
    }

    private fun zoneOf(stations: List<CellZoneStation>, cell: String): IntRange? {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for ((i, st) in stations.withIndex()) {
            if (st.cells.contains(cell)) {
                val p = i + 1
                if (p < min) min = p
                if (p > max) max = p
            }
        }
        return if (min == Int.MAX_VALUE) null else min..max
    }

    private fun stationNameOf(position: Int): String? =
        stationNameAt(position)?.let { id ->
            // 显示用站名由调用方（会话层）经 RouteSpec 提供；跟踪器只回传 id，
            // 这里截取 id 尾段保证可读。真正的站名显示走 MonitorUiState.zoneStation。
            id
        }
}

/** 站区转移（跟踪器 → 会话层） */
sealed class ZoneTransition {
    /** 确认进区（驻留确认通过） */
    data class Entered(val tMs: Long, val range: IntRange) : ZoneTransition()

    /** 退区 */
    data class Exited(val tMs: Long) : ZoneTransition()
}
