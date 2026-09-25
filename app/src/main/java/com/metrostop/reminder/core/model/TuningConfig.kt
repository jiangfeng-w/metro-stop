package com.metrostop.reminder.core.model

/**
 * 全部阈值 / 滤波参数 / 兜底时长的**唯一来源**（硬性规则 2）。
 *
 * 调参闭环：导出实测 CSV → 离线回放验证 → 新参数写回本文件 → 留一个 replay 回归用例。
 * 任何其它文件都不得再写死数值阈值；需要新阈值时在本类新增字段。
 */
data class TuningConfig(
    // ---------- 传感器 ----------
    /** 目标采样率（Hz）；TYPE_LINEAR_ACCELERATION 无此值时按 50 Hz 正常取 */
    val targetSampleHz: Int = 50,

    // ---------- 振动判据（构成滞回，总纲 5.3）----------
    /** 「在动」阈值：vib 高于它视为列车仍在行驶 */
    val vibRunTh: Float = 0.25f,
    /** 「停了」阈值：vib 低于它视为列车已停稳 */
    val vibStopTh: Float = 0.08f,

    // ---------- 制动判据 ----------
    /** 制动判据：H 高于它视为正在制动 / 加减速 */
    val brakeAccelTh: Float = 0.40f,
    /** 制动的最短持续时间，低于它不认定为制动（过滤抖动） */
    val brakeMinSec: Double = 3.0,
    /** 制动的最长持续时间，超过它认为是曲线 / 缓行，放弃该次制动 */
    val brakeMaxSec: Double = 30.0,
    /** 制动释放：H 回落到阈值以下并持续这么久，视为该次制动结束 */
    val brakeReleaseSec: Double = 2.0,

    // ---------- 停稳 / 起步 ----------
    /** 停稳确认时长：静止累计达到它才算「到站」 */
    val stillConfirmSec: Double = 8.0,
    /** 起步确认时长：vib 持续高于「在动」阈值达它才算离站 */
    val departConfirmSec: Double = 5.0,
    /** 两站最短间隔：短于它记 STOP_SUSPECT，不计数 */
    val minStopIntervalSec: Double = 60.0,

    // ---------- 滤波参数 ----------
    /** H 特征（纵向加减速）的低通截止频率 */
    val slowLpfHz: Double = 0.4,
    /** vib 带通下限 */
    val vibLowHz: Double = 3.0,
    /** vib 带通上限 */
    val vibHighHz: Double = 20.0,
    /** vib 的 RMS 统计窗长 */
    val vibWindowSec: Double = 1.0,
    /** 重力方向估计的低通截止频率（用于把去重力向量投影到水平面） */
    val gravityLpfHz: Double = 0.05,
    /** 二阶 IIR 的 Q 值（RBJ cookbook 默认） */
    val iirQ: Double = 0.7071,

    // ---------- 兜底 ----------
    /** 开始后的预热时长：期间不判定 */
    val startGraceSec: Double = 20.0,
    /**
     * 预热期内，振动持续这么久即判定「监测开始时列车已在行驶」。
     * 用于区分两种开始姿势（决定首站是否忽略）：
     * - 在上车站台开始（开始即静止）→ 第一次停站是上车站本身，应忽略；
     * - 已在车上中途开始（开始即在动）→ 第一次停站就是真实的第 1 站，必须计数。
     */
    val startMovingConfirmSec: Double = 1.0,
    /** 断流恢复后的重新预热时长 */
    val warmupResumeSec: Double = 5.0,
    /** 最长监测时长（分钟），超过自动结束 */
    val maxMonitorMin: Double = 90.0,
    /** 到目的站后的自动结束延时 */
    val endGraceSec: Double = 30.0,
    /** 采样断流阈值：相邻样本间隔超过它视为数据缺口 */
    val dataGapSec: Double = 5.0,
) {
    companion object {
        /** 出厂默认参数（mutable 版本供调参面板使用） */
        val Default: TuningConfig = TuningConfig()
    }
}
