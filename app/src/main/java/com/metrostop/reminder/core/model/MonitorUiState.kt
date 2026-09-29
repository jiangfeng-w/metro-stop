package com.metrostop.reminder.core.model

/**
 * UI / 磁贴 / 小组件唯一可读的状态快照（状态来源单一：服务写、其它只读）。
 *
 * 纯 Kotlin data class：core 与 platform 共用，core 不依赖 android.*。
 */
data class MonitorUiState(
    val running: Boolean = false,
    val state: DetectorState = DetectorState.IDLE,
    val lineName: String? = null,
    val directionName: String? = null,
    val boardingStation: String? = null,
    val destinationStation: String? = null,
    /** 已确认到站数 n */
    val stationCount: Int = 0,
    /** 目的站方向的站序号上「当前站」 = 上车站 + n */
    val currentStation: String? = null,
    val nextStation: String? = null,
    /** 剩余站数 */
    val remaining: Int = 0,
    val totalStops: Int = 0,
    val elapsedSec: Double = 0.0,
    val vib: Float = 0f,
    val h: Float = 0f,
    /** 最近一条事件的可读描述（调试面板显示） */
    val lastEvent: String? = null,
    val lastEventMs: Long = 0L,
    /** 是否处于断流状态（异常通知 + 调试面板告警） */
    val dataGap: Boolean = false,
    /** 已排定自动结束（到站后 endGraceSec） */
    val endScheduled: Boolean = false,
    val arrived: Boolean = false,
    /** 是否在用手持 / 外放等高振动基线场景（仅供参考） */
    val recording: Boolean = false,
    val csvDir: String? = null,
    /** 传感器实际采样率（Hz），调试面板显示 */
    val measuredHz: Double = 0.0,
    /** true = TYPE_LINEAR_ACCELERATION；false = 降级 TYPE_ACCELEROMETER */
    val usingLinearSensor: Boolean = true,
    val error: String? = null,
    /**
     * v4 蜂窝站区（cell-zone-detector-v4）：当前确认所在站区的显示名（非空 = 在某站区内）。
     * 由小区序列驱动，比 IMU 状态机更贴近「车在哪个站」的事实；UI 可显示「进站中·<站区>」。
     */
    val zoneStation: String? = null,
)
