package com.metrostop.reminder.core.model

/**
 * 事件类型：同时是 events CSV 的 `type` 列取值。
 *
 * 命名与总纲 7.2 对齐（STATION_ARRIVED / ALERT_PREV / ALERT_ARRIVED / STOP_SUSPECT /
 * DATA_GAP / CORRECTION_UP / MONITOR_END 等），另补状态机内部事件便于离线分析。
 */
enum class DetectorEventType {
    /** 开始监测 */
    MONITOR_START,

    /** 预热结束，进入巡航（此后到站才计数） */
    WARMUP_DONE,

    /** 进入制动 */
    BRAKE_START,

    /** 制动放弃（超时 / 曲线缓行，振动回升） */
    BRAKE_ABORT,

    /** 疑似停稳（振动跌落） */
    STOPPING,

    /** 到站（计数点） */
    STATION_ARRIVED,

    /** 起步前（hasRun=false）的第一个到站：视为上车站本身，不计数 */
    FIRST_STOP_IGNORED,

    /** 疑似到站但不计数（距上一站 < minStopIntervalSec） */
    STOP_SUSPECT,

    /**
     * 到站判定被乘车证据门拦截（近期无乘车证据带：站台静立 / 走路后站住等非乘车停顿）。
     * 仅进 events CSV 供离线分析，不产生任何用户可见反馈。
     */
    EVIDENCE_BLOCKED,

    /** 离站 */
    DEPART,

    /** 数据断流（采样缺口） */
    DATA_GAP,

    /** 数据恢复 */
    DATA_RESUME,

    /** 即将到达目的站（n == k-1） */
    ALERT_PREV,

    /** 已到达目的站（n == k） */
    ALERT_ARRIVED,

    /** 可能已坐过目的站（n > k） */
    OVERSHOOT,

    /** 手动纠正：多过了一站 */
    CORRECTION_UP,

    /** 手动纠正：少算了一站 */
    CORRECTION_DOWN,

    /** 自动结束（到站延时 / 超时兜底 / 手动结束） */
    MONITOR_END,
}

/** 监听结束原因，写入 events CSV 的 note 列 */
object EndReason {
    const val MANUAL = "manual"
    const val ARRIVED = "arrived"
    const val TIMEOUT = "timeout"
}

/**
 * 一条事件（纯 Kotlin）。
 *
 * @param tMs 设备单调时钟（毫秒，与 sensor.csv 的 t_ms 同一时间轴）
 * @param stationIndex 目的站方向上的站序号（0 基，来自 RouteSpec）；不涉及站点时为 null
 * @param stationName 站名（提醒文案永远带站名，跳站快车时以站名为准）
 * @param dwellS 本次停站时长（离站时才有值）
 * @param intervalS 距上一站的间隔（到站时才有值）
 */
data class DetectorEvent(
    val tMs: Long,
    val type: DetectorEventType,
    val stationIndex: Int? = null,
    val stationName: String? = null,
    val dwellS: Double? = null,
    val intervalS: Double? = null,
    val note: String? = null,
)
