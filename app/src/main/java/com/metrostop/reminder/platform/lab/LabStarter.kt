package com.metrostop.reminder.platform.lab

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Lab 采集服务启动入口（镜像 `MonitorStarter`：catch FGS 启动异常，可见反馈，禁止静默失败）。
 *
 * 通知按钮用 `PendingIntent.getService` 直达服务（不走本类，无后台启动问题，2026-09-27）；
 * 本类供 **App 内（前台）** 调用。
 */
object LabStarter {

    /** MARK 动作携带的场景 id extra（LabScenarios.Scenario.id；缺省 = 无语义兜底标记） */
    const val EXTRA_SCENARIO = "lab_scenario_id"

    fun start(context: Context, onFail: (String) -> Unit = {}) {
        val intent = Intent(context, LabCollectorService::class.java)
            .setAction(LabCollectorService.ACTION_START)
        send(context, intent, onFail)
    }

    fun action(context: Context, actionName: String, onFail: (String) -> Unit = {}) {
        send(context, Intent(context, LabCollectorService::class.java).setAction(actionName), onFail)
    }

    /** 带场景 id 的标记动作 */
    fun mark(context: Context, scenarioId: String, onFail: (String) -> Unit = {}) {
        val intent = Intent(context, LabCollectorService::class.java)
            .setAction(LabCollectorService.ACTION_MARK)
            .putExtra(EXTRA_SCENARIO, scenarioId)
        send(context, intent, onFail)
    }

    private fun send(context: Context, intent: Intent, onFail: (String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            onFail(e.message ?: e.javaClass.simpleName)
        }
    }
}
