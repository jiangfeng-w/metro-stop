package com.metrostop.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.metrostop.reminder.platform.notify.Notifications
import com.metrostop.reminder.platform.service.MonitorService
import com.metrostop.reminder.platform.service.MonitorStarter
import com.metrostop.reminder.ui.AppScreen
import com.metrostop.reminder.ui.AppViewModel

/**
 * 单 Activity 单屏（总纲第八节）。targetSdk 36 强制 edge-to-edge。
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_START_ERROR = "start_error"
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.ensureChannels(this)

        val vm = AppViewModel(applicationContext)
        viewModel = vm
        requestNotifIfNeeded()

        setContent {
            MaterialTheme {
                Surface {
                    AppScreen(vm)
                }
            }
        }
        handleIntent(intent, vm)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 磁贴 / 通知二次拉起（singleTop）也要生效
        viewModel?.let { handleIntent(intent, it) }
    }

    private var viewModel: AppViewModel? = null

    /** 处理 EXTRA_AUTO_START / EXTRA_START_ERROR（App 可见 → 前台启动 FGS 合法） */
    private fun handleIntent(intent: Intent?, vm: AppViewModel) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.removeExtra(EXTRA_AUTO_START) // 避免旋转 / 重建时重复触发
            vm.startWithLastRoute { err ->
                if (err != null) vm.reportError(getString(R.string.err_start_failed, err))
            }
        }
        intent.getStringExtra(EXTRA_START_ERROR)?.let { err ->
            intent.removeExtra(EXTRA_START_ERROR)
            vm.reportError(getString(R.string.err_start_failed, err))
        }
    }

    private fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
