package com.metrostop.reminder.core.route

import com.metrostop.reminder.core.model.Direction
import com.metrostop.reminder.core.model.Line
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.SubwayData
import kotlinx.serialization.json.Json

/**
 * 线路数据仓库（纯 Kotlin 解析 + 查询；assets 读取由 platform 层负责）。
 *
 * 校验：方向内站 id 唯一、线路 / 方向 id 唯一；异常数据不抛异常，只丢弃非法项并返回可读错误。
 */
class LineRepository private constructor(
    private val data: SubwayData,
) {
    val city: String get() = data.city
    val lines: List<Line> get() = data.lines

    fun line(lineId: String): Line? = data.lines.firstOrNull { it.id == lineId }

    fun direction(lineId: String, directionId: String): Direction? =
        line(lineId)?.directions?.firstOrNull { it.id == directionId }

    /** 目的站候选项：方向中排在上车站**之后**的所有站 */
    fun destinationCandidates(lineId: String, directionId: String, boardingStationId: String): List<com.metrostop.reminder.core.model.Station> {
        val d = direction(lineId, directionId) ?: return emptyList()
        val bi = d.stations.indexOfFirst { it.id == boardingStationId }
        if (bi < 0) return emptyList()
        return d.stations.drop(bi + 1)
    }

    /** 当前是否构成合法路线（用于 UI 按钮可用性） */
    fun isValid(
        lineId: String?,
        directionId: String?,
        boardingId: String?,
        destinationId: String?,
    ): Boolean {
        if (lineId == null || directionId == null || boardingId == null || destinationId == null) return false
        val line = line(lineId) ?: return false
        val d = direction(lineId, directionId) ?: return false
        return RouteSpec.of(line, d, boardingId, destinationId) != null
    }

    fun buildRoute(
        lineId: String,
        directionId: String,
        boardingId: String,
        destinationId: String,
    ): RouteSpec? {
        val line = line(lineId) ?: return null
        val d = direction(lineId, directionId) ?: return null
        return RouteSpec.of(line, d, boardingId, destinationId)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parse(text: String): Result<LineRepository> = runCatching {
            val data = json.decodeFromString(SubwayData.serializer(), text)
            val cleaned = data.copy(
                lines = data.lines
                    .filter { it.id.isNotBlank() && it.directions.isNotEmpty() }
                    .distinctBy { it.id }
                    .map { line ->
                        line.copy(
                            directions = line.directions
                                .filter { it.id.isNotBlank() && it.stations.isNotEmpty() }
                                .distinctBy { it.id }
                                .map { dir ->
                                    dir.copy(
                                        // 同方向内站 id 去重，防止坏数据破坏 index 语义
                                        stations = dir.stations.distinctBy { it.id },
                                    )
                                },
                        )
                    },
            )
            LineRepository(cleaned)
        }

        /** 空仓库（assets 读取失败时的降级，UI 显示错误但不崩） */
        fun empty(): LineRepository = LineRepository(SubwayData())
    }
}
