package com.metrostop.reminder.platform.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 红米 / HyperOS 保活五步（总纲第六节）跳转 best-effort：失败回退应用详情页。
 * 桌面小组件（M3）与调试面板（M1）共用。
 */
object KeepAliveHelper {

    data class Step(
        val title: String,
        val desc: String,
        val intents: List<Intent>,
    )

    fun steps(context: Context): List<Step> {
        val pkg = context.packageName
        return listOf(
            Step(
                title = "1. 通知权限与渠道",
                desc = "允许「到站了」发送通知，并确认提醒渠道未被静音。",
                intents = listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)),
            ),
            Step(
                title = "2. 省电策略「无限制」",
                desc = "设置 → 应用设置 → 到站了 → 省电策略 → 无限制；否则灭屏后易被冻结。",
                intents = listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(pkg)),
            ),
            Step(
                title = "3. 自启动",
                desc = "在「自启动管理」中允许「到站了」自启动。",
                intents = listOf(autostart(context), appDetails(pkg)),
            ),
            Step(
                title = "4. 后台弹出界面 / 锁屏通知",
                desc = "允许后台弹出界面与锁屏显示通知，保证提醒可见。",
                intents = listOf(permissionEditor(context), appDetails(pkg)),
            ),
            Step(
                title = "5. 最近任务加锁",
                desc = "在多任务界面下拉「到站了」卡片加锁，避免被一键清理。",
                intents = listOf(appDetails(pkg)),
            ),
        )
    }

    /** 依次尝试候选 Intent，都失败则打开应用详情页（best-effort，绝不崩） */
    fun open(context: Context, step: Step): Boolean {
        for (intent in step.intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return runCatching {
            context.startActivity(appDetails(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(appDetails(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appDetails(pkg: String) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$pkg"),
    )

    private fun autostart(context: Context): Intent =
        Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }

    private fun permissionEditor(context: Context): Intent =
        Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            putExtra("extra_pkgname", context.packageName)
        }
}
