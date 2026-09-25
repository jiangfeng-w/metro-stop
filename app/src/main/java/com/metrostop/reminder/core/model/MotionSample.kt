package com.metrostop.reminder.core.model

/**
 * 一条传感器样本（纯 Kotlin，不含 android.*）。
 *
 * 时间：`tMs` 为设备单调时钟（elapsedRealtime，用于时长计算），`utcMs` 为墙钟（仅记录）。
 * 加速度单位 m/s²；陀螺仪单位 rad/s。线性加速度 / 陀螺仪不可用时为 null（降级模式）。
 */
data class MotionSample(
    val tMs: Long,
    val utcMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val lx: Float? = null,
    val ly: Float? = null,
    val lz: Float? = null,
    val gx: Float? = null,
    val gy: Float? = null,
    val gz: Float? = null,
) {
    /** 是否带系统级去重力线性加速度（TYPE_LINEAR_ACCELERATION） */
    val hasLinear: Boolean
        get() = lx != null && ly != null && lz != null
}

/** 特征提取结果：H = 纵向加减速（刹车判据），vib = 3–20 Hz 振动 RMS（行驶 / 停稳判据） */
data class Features(
    val tMs: Long,
    val h: Float,
    val vib: Float,
)
