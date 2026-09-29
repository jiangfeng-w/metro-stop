package com.metrostop.reminder.core.cell

import com.metrostop.reminder.core.model.RouteSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 蜂窝站区映射（v4，cell-zone-detector-v4；assets/cell_zones.json）。
 *
 * 数据来源：学习期通勤的 lab `cellid` 流 + 「列车进站停稳」MARK 真值离线对齐
 * （生成脚本 `docs/spec/active/cell-zone-detector-v4/assets/gen_cell_zones.py`）。
 * 隐私：仅含 pci:ci（运营商网络标识，需求 3.3 判定可直接落盘）与公开站序，无 GPS / 无轨迹。
 *
 * 结构：线路+方向 → 站区序列（**进站顺序**，position = 1..k 对应路线计数 1..k）。
 * 同一 pci:ci 可对应相邻多站（实测 4 号线 `701:38658715650` 一段覆盖市二医院+太升南路），
 * 因此站区的判定结果是**位置区间** [min..max] 而非单点。
 */
@Serializable
data class CellZoneData(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val note: String = "",
    val lines: List<CellZoneLine> = emptyList(),
)

@Serializable
data class CellZoneLine(
    val lineId: String,
    val directionId: String,
    val learnedAt: String = "",
    /** 生成该映射使用的学习趟数（置信度参考：1 趟 = 自举，≥2 趟才允许切主通道） */
    val rideCount: Int = 0,
    /** 站区序列，按进站顺序；stations[i] 对应路线计数 position = i+1 */
    val stations: List<CellZoneStation> = emptyList(),
)

@Serializable
data class CellZoneStation(
    val stationId: String,
    val cells: List<String> = emptyList(),
)

class CellZoneMap private constructor(private val data: CellZoneData) {

    /**
     * 取路线对应的站区表；**站序校验失败返回 null（降级为 v3 行为）**。
     * 映射的站序必须与「上车站之后的到达站序列」逐站一致（允许映射比路线更长，取前缀）。
     */
    fun forRoute(route: RouteSpec): CellZoneLine? {
        val l = data.lines.firstOrNull {
            it.lineId == route.lineId && it.directionId == route.directionId
        } ?: return null
        val expected = route.stations
            .drop(route.boardingIndex + 1)
            .take(route.stopCount)
            .map { it.id }
        if (l.stations.size < expected.size) return null
        for (i in expected.indices) {
            if (l.stations[i].stationId != expected[i]) return null
        }
        return l
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parse(text: String): Result<CellZoneMap> = runCatching {
            val data = json.decodeFromString(CellZoneData.serializer(), text)
            require(data.lines.isNotEmpty()) { "空映射" }
            CellZoneMap(data)
        }

        fun empty(): CellZoneMap = CellZoneMap(CellZoneData())
    }
}
