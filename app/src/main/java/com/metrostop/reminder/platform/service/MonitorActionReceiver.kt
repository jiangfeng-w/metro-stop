package com.metrostop.reminder.platform.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.metrostop.reminder.platform.notify.Notifier

/**
 * 通知按钮接收器（硬性规则 6）：**动态注册**（REGISTER_NOT_RECEIVER_EXPORTED），
 * 不在清单里静态声明；收到按钮动作后只做一件事 —— 把 ACTION 转给 MonitorService。
 *
 * 注意：通知按钮「结束 / 我已多过一站」在官方后台启动 FGS 豁免清单内，
 * 但仍按硬性规则 5 catch，失败时拉起 App 界面（此处通过 UI 的提示路径处理）。
 */
class MonitorActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifier.ACTION_OPEN_APP -> openApp(context)
            Notifier.ACTION_STOP -> MonitorStarter.action(context, MonitorService.ACTION_STOP)
            // 「我已多过一站」= 实际位置比 App 计数更靠前 → 计数 +1（对应漏检）
            Notifier.ACTION_MISSED_ONE -> MonitorStarter.action(context, MonitorService.ACTION_CORRECT_UP)
        }
    }

    private fun openApp(context: Context) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) context.startActivity(launch)
        }
    }
}
