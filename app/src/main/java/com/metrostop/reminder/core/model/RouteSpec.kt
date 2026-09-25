package com.metrostop.reminder.core.model

import kotlinx.serialization.Serializable

/** 线路 JSON（assets/subway_lines.json）的映射类型；方向显式写两遍，便于人工核对 */
@Serializable
data class SubwayData(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val city: String = "",
    val note: String = "",
    val lines: List<Line> = emptyList(),
)

@Serializable
data class Line(
    val id: String,
    val name: String,
    val directions: List<Direction>,
)

@Serializable
data class Direction(
    val id: String,
    val name: String,
    val stations: List<Station>,
)

@Serializable
data class Station(
    val id: String,
    val name: String,
)

/**
 * 「一次出行」的路线规格：某个方向上的上车站 → 目的站。
 *
 * 计数语义（总纲 5.4）：`k = index(D) − index(S)`；`n` = 已确认到站数；
 * 当前站 = `S + n`；下一站 = `S + n + 1`；剩余 = `k − n`。
 */
data class RouteSpec(
    val lineId: String,
    val lineName: String,
    val directionId: String,
    val directionName: String,
    val stations: List<Station>,
    val boardingIndex: Int,
    val destinationIndex: Int,
) {
    /** 需要经过的站数（上车站不计入到站数） */
    val stopCount: Int get() = destinationIndex - boardingIndex

    val boardingStation: Station get() = stations[boardingIndex]
    val destinationStation: Station get() = stations[destinationIndex]

    /** 按已确认到站数 n 推算当前站；可能溢出行数范围时钳制 */
    fun currentStationIndex(n: Int): Int =
        (boardingIndex + n).coerceIn(0, stations.lastIndex)

    fun currentStation(n: Int): Station = stations[currentStationIndex(n)]

    /** 下一站；n >= k（已到目的站）时返回目的站本身 */
    fun nextStationIndex(n: Int): Int =
        (boardingIndex + n + 1).coerceIn(0, stations.lastIndex)

    fun nextStation(n: Int): Station = stations[nextStationIndex(n)]

    /** 剩余站数，最小 0 */
    fun remaining(n: Int): Int = (stopCount - n).coerceAtLeast(0)

    /** 站序（0 基）→ 站名 */
    fun stationNameAt(index: Int): String? = stations.getOrNull(index)?.name

    companion object {
        /**
         * 由方向 + 上车站 + 目的站构造；校验目的站必须在上车站之后，否则返回 null。
         */
        fun of(line: Line, direction: Direction, boardingId: String, destinationId: String): RouteSpec? {
            val bi = direction.stations.indexOfFirst { it.id == boardingId }
            val di = direction.stations.indexOfFirst { it.id == destinationId }
            if (bi < 0 || di < 0 || di <= bi) return null
            return RouteSpec(
                lineId = line.id,
                lineName = line.name,
                directionId = direction.id,
                directionName = direction.name,
                stations = direction.stations,
                boardingIndex = bi,
                destinationIndex = di,
            )
        }
    }
}
