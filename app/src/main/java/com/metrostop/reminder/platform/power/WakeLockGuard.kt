package com.metrostop.reminder.platform.power

import android.content.Context
import android.os.PowerManager

/**
 * PARTIAL_WAKE_LOCK 守卫：带超时，停止即释放（总纲第六节，预计 1–3 %/小时）。
 */
class WakeLockGuard(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lock: PowerManager.WakeLock? = null

    fun acquire(timeoutMs: Long) {
        if (lock == null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
            }
        }
        val l = lock ?: return
        if (!l.isHeld) {
            l.acquire(timeoutMs)
        }
    }

    fun release() {
        val l = lock ?: return
        if (l.isHeld) {
            runCatching { l.release() }
        }
    }

    companion object {
        private const val TAG = "MetroStop:cpu"
    }
}
