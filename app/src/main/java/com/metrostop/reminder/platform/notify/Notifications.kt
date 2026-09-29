package com.metrostop.reminder.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 通知 3 渠道（硬性规则 4：**属性创建后系统不可更改**，M2 一次定稿；之后只允许改文案）。
 *
 * - `monitor_ongoing`：LOW，常驻进度「剩 N 站」，无震动；
 * - `station_alert`：HIGH + 响铃（**震动由 App 侧主动执行**，见 `VibratorHelper`）；
 * - `station_alert_silent`：HIGH 静音（同样由 App 侧震动）。
 *
 * **震动为何不放在渠道上**（2026-09-25 红米 K80 / HyperOS 4 beta 实测）：
 * 渠道 `enableVibration(true)` + `vibrationPattern` 配置正确但弹通知时无震动
 * （HyperOS 对通知震动另有系统级开关 / 策略干预）。改由 App 主动调用 `Vibrator`：
 * 可靠、与系统通知震动设置解耦，且避免「渠道震动 + App 震动」双重震动。
 *
 * 渠道 id / 重要性改动后需要**卸载重装**才生效（debug 无成本）。
 */
object Notifications {

    const val CH_ONGOING = "monitor_ongoing"
    const val CH_ALERT = "station_alert"
    const val CH_ALERT_SILENT = "station_alert_silent"

    const val ID_ONGOING = 1010
    const val ID_PREV = 1020
    const val ID_DEST_SOON = 1025
    const val ID_ARRIVED = 1030
    const val ID_OVERSHOOT = 1040
    const val ID_ERROR = 1050
    const val ID_TEST = 1060
    const val ID_KEEPALIVE = 1070

    /** 结束收尾通知（M2）：复用 ongoing 渠道（LOW），不新建渠道、不改任何渠道属性 */
    const val ID_END = 1080

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ongoing = NotificationChannel(
            CH_ONGOING,
            "监测进行中",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示剩余站数与当前状态"
            setShowBadge(false)
            enableVibration(false)
        }

        // 震动关闭：改由 App 侧 VibratorHelper 主动执行（HyperOS 渠道震动不生效）
        val alert = NotificationChannel(
            CH_ALERT,
            "到站提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "目的站前一站提醒与到站确认"
            enableVibration(false)
        }

        val silent = NotificationChannel(
            CH_ALERT_SILENT,
            "到站提醒（仅震动）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "只震动不出声（App 内切换）；震动由 App 主动执行"
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannels(listOf(ongoing, alert, silent))
    }
}
