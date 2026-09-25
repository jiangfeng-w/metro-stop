package com.metrostop.reminder.platform.notify

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动执行器：**应用侧主动调用**，不依赖通知渠道的震动属性。
 *
 * ## 为什么不用渠道震动（2026-09-25 红米 K80 / HyperOS 4 beta 实测）
 * 渠道 `enableVibration(true)` + `vibrationPattern` 配置正确（`dumpsys notification` 可查），
 * 但弹通知时手机不震动 —— HyperOS 对通知震动有额外系统级干预。
 *
 * ## 为什么必须带 VibrationAttributes（真机 `dumpsys vibrator_manager` 实证）
 * 早期实现用 `VibrationEffect.createWaveform(pattern, -1)`（无 attributes），系统日志为：
 * ```
 * ignored_for_settings | usage: UNKNOWN | com.metrostop.reminder
 * ```
 * 因为 `VibrationSettings` 的 `VibrationIntensities` 里 **`UNKNOWN = OFF`**（而 `ALARM` / `NOTIFICATION`
 * / `RINGTONE` 均为 `MEDIUM` 可用）→ 无 attributes 的震动会被系统直接丢弃。
 * 因此必须用 API 33+ 的 `vibrate(VibrationEffect, VibrationAttributes)` 显式声明 usage。
 *
 * 选用 **`USAGE_ALARM`**：到站提醒属于强提醒（用户可能睡着 / 车厢嘈杂），
 * 且 ALARM 在系统里默认可用、受「勿扰」策略的约束比 NOTIFICATION 更宽松。
 * 同时带上 `AudioAttributes.USAGE_ALARM` 作为兼容 API 26~32 的通道标识。
 */
class VibratorHelper(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 到站提醒：两下长震（400 - 200 - 400 ms） */
    fun vibrateAlert() {
        vibrate(longArrayOf(0, 400, 200, 400))
    }

    /** 起步 / 测试 / 异常等轻量反馈：单下短震 */
    fun vibrateShort() {
        vibrate(longArrayOf(0, 150))
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+：显式带 usage，否则被系统按 UNKNOWN(=OFF) 丢弃
                v.vibrate(effect, alarmAttributes())
            } else {
                // API 26~32：没有 VibrationAttributes；改用 AudioAttributes 重载声明 ALARM 通道
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun alarmAttributes(): VibrationAttributes =
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM)
            .build()
}
