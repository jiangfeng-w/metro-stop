package com.metrostop.reminder.platform.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.metrostop.reminder.R

/**
 * Lab 采集的通知（**新增独立渠道 `lab_ongoing`，不动硬性规则 4 定稿的三渠道**）。
 * LOW 重要性、无震动；按钮「📍标记」「停止」。
 *
 * ## 按钮为什么用 `PendingIntent.getService` 直达服务（2026-09-27 实测修订）
 * 首版走「broadcast → LabActionReceiver → startForegroundService」二跳，真机点按无反应：
 * Android 12+ 对「从 BroadcastReceiver 后台 startForegroundService」不提供通知按钮豁免，
 * HyperOS 直接拒绝（ForegroundServiceStartNotAllowedException），且首版 LabStarter 默认
 * onFail 为空 → **静默失败**。改为 PendingIntent **getService** 直达 LabCollectorService：
 * - 服务本就是运行中的 FGS，投递 startIntent 只是转发 onStartCommand，无后台启动问题；
 * - 减一跳，无接收器（显式 Intent + FLAG_IMMUTABLE + 唯一 requestCode，硬性规则 6 的安全意图不变）；
 * - 服务即使被杀后点按，onStartCommand 首行也会先 startForeground 再按 ACTION_STOP/MARK 处理，合法。
 */
object LabNotifications {

    const val CH_LAB = "lab_ongoing"
    const val ID_LAB = 2010

    const val RC_MARK = 3001
    const val RC_STOP = 3002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CH_LAB,
            "实验室数据采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "调试用数据采集的常驻通知（短期需求，采集结束即移除）"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    /** 通知按钮：getService **直达** LabCollectorService（显式 Intent + IMMUTABLE + 唯一 requestCode） */
    private fun pi(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, LabCollectorService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 常驻通知：计时器自动走动 + 当前场景名（同主通知 chronometer 方案，无需每秒重贴） */
    fun buildOngoing(context: Context, startedElapsedMs: Long, marks: Int, currentScenario: String? = null): Notification {
        val startedAtWallClock = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedElapsedMs)
        val text = if (currentScenario != null) {
            context.getString(R.string.lab_notif_text_scenario, marks, currentScenario)
        } else {
            context.getString(R.string.lab_notif_text, marks)
        }
        return NotificationCompat.Builder(context, CH_LAB)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(context.getString(R.string.lab_notif_title))
            .setContentText(text)
            .setShowWhen(true)
            .setWhen(startedAtWallClock)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .addAction(0, context.getString(R.string.lab_action_mark), pi(context, RC_MARK, LabCollectorService.ACTION_MARK))
            .addAction(0, context.getString(R.string.lab_action_stop), pi(context, RC_STOP, LabCollectorService.ACTION_STOP))
            .build()
    }
}
