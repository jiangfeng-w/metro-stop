package com.metrostop.reminder.core.feature

import com.metrostop.reminder.core.model.TuningConfig

/**
 * 步态门（v3，multi-signal-detector-v3）：用陀螺仪区分「人在走」与「没在走」。
 *
 * 依据（2026-09-28 早/晚通勤 lab 数据标定，gyro 1 s RMS）：
 * - 站厅走路 / 楼梯：1.18~1.31（p50），p95 ≥ 2.5；
 * - 站台静立等车：0.20（p50）；
 * - 站姿乘车（握扶手）：0.11~0.14（p50）；
 * - 坐姿玩手机：0.19（p50）。
 *
 * 判据：`walkGyroTh`(0.6) 位于走路与全部非走路场景的中点，单票制。
 * FINDINGS 警告的「0.8–4 Hz 步频周期谱二票」未经验证，**本版不实现**（待真实数据回放验证后另行评估）。
 *
 * 全因果：只用当前与历史样本（1 s RMS 窗 + 连续确认），CSV 回放 ≡ 现场（硬性规则 1 前提）。
 * 陀螺不可用（`gyroMag == 0`）时输出「没在走」——降级语义：宁可不拦，不可误拦乘车。
 */
class GaitGate(private val config: TuningConfig) {

    private val window = RingBuffer((config.targetSampleHz * config.gyroWindowSec).toInt().coerceAtLeast(1))

    /** 当前是否判定「人在走」（带 confirm 后的输出） */
    var isWalking: Boolean = false
        private set

    private var confirmForSec = 0.0
    private var prevTMs = 0L

    fun reset() {
        window.clear()
        isWalking = false
        confirmForSec = 0.0
        prevTMs = 0L
    }

    /** 处理一条特征（GaitGate 只关心 tMs 与 gyroMag）；返回当前 isWalking */
    fun process(tMs: Long, gyroMag: Float): Boolean {
        val dtSec = if (prevTMs == 0L) 0.0 else ((tMs - prevTMs).coerceAtLeast(0L)) / 1000.0
        prevTMs = tMs

        // 1 s RMS 窗（与 vib 同构；gyroMag==0 = 无陀螺数据，样本不入窗）
        if (gyroMag > 0f) {
            window.push(gyroMag)
        }
        val rms = if (window.count > 0) window.rms() else 0f

        // 步行候选：RMS 超阈值（窗未满时 rms 偏低，天然保守）
        val candidate = gyroMag > 0f && rms > config.walkGyroTh

        if (candidate) {
            confirmForSec += dtSec
            if (confirmForSec >= config.gaitConfirmSec) isWalking = true
        } else {
            // 退出带容差（走出阈值 <gaitReleaseSec 视为抖动，不立刻退出）
            confirmForSec = 0.0
            if (isWalking && rms < config.walkGyroTh * 0.8f) {
                releaseForSec += dtSec
                if (releaseForSec >= config.gaitReleaseSec) isWalking = false
            } else if (rms >= config.walkGyroTh * 0.8f) {
                releaseForSec = 0.0
            }
        }
        return isWalking
    }

    private var releaseForSec = 0.0
}
