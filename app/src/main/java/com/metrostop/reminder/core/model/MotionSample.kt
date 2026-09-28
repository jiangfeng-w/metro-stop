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

    /** 陀螺仪三轴幅值（rad/s）；不可用为 null */
    val gyroMag: Float?
        get() {
            val x = gx ?: return null
            val y = gy ?: return null
            val z = gz ?: return null
            return kotlin.math.sqrt(x * x + y * y + z * z)
        }
}

/**
 * 特征提取结果（v3 起含陀螺幅值）：
 * - `H` = 纵向加减速（刹车判据）；
 * - `vib` = 3–20 Hz 振动 RMS（行驶 / 停稳判据）；
 * - `gyroMag` = 陀螺三轴幅值原值（rad/s，GaitGate 内做 1 s RMS；不可用时为 0，GaitGate 视为「无陀螺数据」降级）。
 */
data class Features(
    val tMs: Long,
    val h: Float,
    val vib: Float,
    val gyroMag: Float = 0f,
)
