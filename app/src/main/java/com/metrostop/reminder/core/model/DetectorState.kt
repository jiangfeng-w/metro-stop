package com.metrostop.reminder.core.model

/** 状态机状态（总纲 5.2） */
enum class DetectorState {
    /** 未开始 */
    IDLE,

    /** 预热中：开始后 startGraceSec 内不判定 */
    WARMUP,

    /** 巡航：列车行驶中，等待制动 */
    CRUISE,

    /** 制动中：H 持续高于阈值 */
    BRAKING,

    /** 疑似停稳：振动已跌落，等待静止确认 */
    STOPPING,

    /** 已停稳（到站计数点） */
    STOPPED,

    /** 起步中：振动回升，等待起步确认 */
    DEPARTING,

    /** 断流：采样缺口超过阈值，恢复后需重新预热 */
    INTERRUPTED,

    /** 已结束 */
    FINISHED,
}
