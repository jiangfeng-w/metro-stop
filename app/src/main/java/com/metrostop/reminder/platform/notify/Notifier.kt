package com.metrostop.reminder.platform.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.metrostop.reminder.R
import com.metrostop.reminder.core.model.DetectorEvent
import com.metrostop.reminder.core.model.DetectorEventType
import com.metrostop.reminder.core.model.MonitorUiState
import com.metrostop.reminder.platform.service.MonitorActionReceiver

/**
 * 全部通知文案与发送的唯一出口（总纲第八节：中文文案集中 strings.xml）。
 *
 * PendingIntent 一律 FLAG_IMMUTABLE + 显式 Intent + **唯一 requestCode**（硬性规则 6）。
 */
class Notifier(
    private val context: Context,
    private val alertSilent: () -> Boolean,
    private val vibrator: VibratorHelper = VibratorHelper(context),
) {

    /**
     * 硬性规则 6：显式 Intent + FLAG_IMMUTABLE + 每个用途唯一 requestCode。
     * 动作按钮走 MonitorActionReceiver（动态注册，RECEIVER_NOT_EXPORTED）。
     */
    private fun pi(requestCode: Int, action: String, extra: Pair<String, String>? = null): PendingIntent {
        val intent = Intent(context, MonitorActionReceiver::class.java).apply {
            this.action = action
            extra?.let { putExtra(it.first, it.second) }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 常驻进度通知（前台服务通知；LOW，无震动）。
     *
     * `setUsesChronometer`：让系统在通知里渲染一个**自动走动的计时器**（从监测开始算起），
     * 无需每秒重新 notify，用户在通知栏即可看到已运行时长（修复「时间没有开始增加」）。
     */
    fun buildOngoing(state: MonitorUiState): android.app.Notification {
        // 未在监测时（前台服务的 5 秒占位 / 测试提醒等瞬时场景）：显示中性文案，避免「当前 -」的怪样子
        val b = NotificationCompat.Builder(context, Notifications.CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi(RC_OPEN_APP, ACTION_OPEN_APP))

        if (!state.running) {
            return b.setShowWhen(false)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_idle_text))
                .build()
        }

        // M2 文案终稿：标题从「当前站」改为「距下车还有 N 站」（高德锁屏「还有 1 站到站」同构），
        // 到站态正文改为动作指令「请下车。」
        val title = if (state.arrived) {
            context.getString(R.string.notif_ongoing_arrived_title, state.destinationStation ?: "-")
        } else {
            context.getString(R.string.notif_ongoing_title, state.remaining)
        }
        val text = if (state.arrived) {
            context.getString(R.string.notif_ongoing_arrived_text)
        } else {
            context.getString(
                R.string.notif_ongoing_text,
                state.nextStation ?: "-",
                state.totalStops,
            )
        }
        // chronometer 以 wall clock 起算：用「当前时间 − 已运行时长」反推开始时刻
        val startedAtWallClock = System.currentTimeMillis() - (state.elapsedSec * 1000).toLong()
        return b.setContentTitle(title)
            .setContentText(text)
            // v4：站区确认时显示「进站中·<站区>」，与状态卡同口径（比 IMU 状态机更贴近事实）
            .setSubText(state.zoneStation?.let { "进站中·$it" } ?: state.state.cnName)
            .setShowWhen(true)
            .setWhen(startedAtWallClock)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .addAction(0, context.getString(R.string.action_missed_one), pi(RC_MISSED_ONE, ACTION_MISSED_ONE))
            .addAction(0, context.getString(R.string.action_stop), pi(RC_STOP, ACTION_STOP))
            .build()
    }

    /** 普通通知（前一站 / 到达 / 坐过站 / 异常 / 测试） */
    private fun buildAlert(
        channelId: String,
        title: String,
        text: String,
        high: Boolean,
        actions: Boolean = true,
    ): android.app.Notification {
        val b = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (high) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pi(RC_OPEN_APP, ACTION_OPEN_APP))
        if (actions) {
            b.addAction(0, context.getString(R.string.action_stop), pi(RC_STOP, ACTION_STOP))
            b.addAction(0, context.getString(R.string.action_missed_one), pi(RC_MISSED_ONE, ACTION_MISSED_ONE))
        }
        return b.build()
    }

    private fun alertChannel(): String =
        if (alertSilent()) Notifications.CH_ALERT_SILENT else Notifications.CH_ALERT

    /**
     * 发事件对应的通知；不在此处判断去重（去重由 MonitorSession / 服务保证）。
     *
     * **震动由 App 侧主动执行**（[VibratorHelper]），不依赖渠道震动属性 ——
     * 2026-09-25 红米 K80 / HyperOS 4 beta 实测渠道震动不生效。
     */
    fun notifyEvent(event: DetectorEvent, state: MonitorUiState) {
        when (event.type) {
            DetectorEventType.ALERT_PREV -> {
                vibrateAlert()
                post(
                    Notifications.ID_PREV,
                    buildAlert(
                        alertChannel(),
                        context.getString(R.string.notif_prev_title),
                        // 高德句式（M2 终稿）：下一站 + 动作指令；站名兜底是总纲红线
                        context.getString(
                            R.string.notif_prev_text,
                            event.stationName ?: state.destinationStation ?: "-",
                        ),
                        high = true,
                    ),
                )
            }

            DetectorEventType.ALERT_DEST_SOON -> {
                vibrateAlert()
                post(
                    Notifications.ID_DEST_SOON,
                    buildAlert(
                        alertChannel(),
                        context.getString(R.string.notif_dest_soon_title),
                        // 高德句式（M2 终稿同源）：进目的站区即发（早于停稳 0~58 s）；站名兜底是总纲红线
                        context.getString(
                            R.string.notif_dest_soon_text,
                            event.stationName ?: state.destinationStation ?: "-",
                        ),
                        high = true,
                    ),
                )
            }

            DetectorEventType.ALERT_ARRIVED -> {
                vibrateAlert()
                post(
                    Notifications.ID_ARRIVED,
                    buildAlert(
                        alertChannel(),
                        context.getString(
                            R.string.notif_arrived_title,
                            event.stationName ?: state.destinationStation ?: "-",
                        ),
                        context.getString(R.string.notif_arrived_text),
                        high = true,
                    ),
                )
            }

            DetectorEventType.OVERSHOOT -> {
                vibrateAlert()
                // 过站后带「下一站可折返」（M2 终稿，可操作化）；下一站缺失时回退保守文案
                val next = state.nextStation
                val text = if (next != null) {
                    context.getString(
                        R.string.notif_overshoot_text,
                        event.stationName ?: "-",
                        next,
                    )
                } else {
                    context.getString(R.string.notif_overshoot_text_fallback, event.stationName ?: "-")
                }
                post(
                    Notifications.ID_OVERSHOOT,
                    buildAlert(
                        alertChannel(),
                        context.getString(R.string.notif_overshoot_title),
                        text,
                        high = true,
                        actions = false,
                    ),
                )
            }

            DetectorEventType.DATA_GAP -> {
                vibrateShort()
                post(
                    Notifications.ID_ERROR,
                    buildAlert(
                        Notifications.CH_ALERT,
                        context.getString(R.string.notif_gap_title),
                        context.getString(R.string.notif_gap_text),
                        high = false,
                        actions = false,
                    ),
                )
            }

            else -> Unit
        }
    }

    /** 「测试提醒」：确认能弹能震（M2 首次启动引导用） */
    fun notifyTest() {
        vibrateAlert()
        post(
            Notifications.ID_TEST,
            buildAlert(
                alertChannel(),
                context.getString(R.string.notif_test_title),
                context.getString(R.string.notif_test_text),
                high = true,
                actions = false,
            ),
        )
    }

    /** 启动 / 运行失败：可见反馈（禁止静默失败） */
    fun notifyError(message: String) {
        vibrateShort()
        post(
            Notifications.ID_ERROR,
            buildAlert(
                Notifications.CH_ALERT,
                context.getString(R.string.notif_error_title),
                message,
                high = true,
                actions = false,
            ),
        )
    }

    /**
     * 结束收尾通知（M2）：手动 / 数满自动 / 90 min 兜底结束统一反馈，让用户明确知道监测真的停了。
     * 复用 ongoing 渠道（LOW，无震动），autoCancel、无动作按钮；error 路径（[notifyError]）不发本条。
     */
    fun notifyEnd(stationCount: Int, elapsedSec: Double) {
        post(
            Notifications.ID_END,
            NotificationCompat.Builder(context, Notifications.CH_ONGOING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notif_end_title))
                .setContentText(
                    context.getString(R.string.notif_end_text, stationCount, formatDuration(elapsedSec)),
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pi(RC_OPEN_APP, ACTION_OPEN_APP))
                .build(),
        )
    }

    private fun formatDuration(sec: Double): String {
        val total = sec.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分钟"
            else -> "不足 1 分钟"
        }
    }

    private fun vibrateAlert() = runCatching { vibrator.vibrateAlert() }

    private fun vibrateShort() = runCatching { vibrator.vibrateShort() }

    private fun post(id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    companion object {
        const val ACTION_OPEN_APP = "com.metrostop.reminder.action.OPEN_APP"
        const val ACTION_STOP = "com.metrostop.reminder.action.STOP"
        const val ACTION_MISSED_ONE = "com.metrostop.reminder.action.MISSED_ONE"

        const val RC_OPEN_APP = 2001
        const val RC_STOP = 2002
        const val RC_MISSED_ONE = 2003
    }
}
