package com.metrostop.reminder.platform.qs

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.metrostop.reminder.MainActivity
import com.metrostop.reminder.R
import com.metrostop.reminder.platform.service.MonitorService
import com.metrostop.reminder.platform.service.MonitorStarter
import com.metrostop.reminder.platform.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * QS 磁贴一键开始（总纲第六节）：**QS 磁贴不在后台启动 FGS 豁免清单**内，
 * 因此启动失败时（try/catch）拉起 App 界面让用户点按钮，绝不静默失败（硬性规则 5）。
 *
 * 只读 SessionHolder（不在主线程读 DataStore，硬性规则 7）。
 *
 * 状态刷新：服务是**异步**启动的，`onClick` 里立即 render 时状态尚未更新；
 * 因此面板展开期间订阅 `SessionHolder.running`，变化即 `updateTile()` ——
 * 否则要收起重开通知栏（触发 onStartListening）才看得到变化。
 */
class MonitorTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        render()
        // 面板可见期间跟踪状态变化（只关心 running 翻转，避免每秒无谓 updateTile）
        watchJob?.cancel()
        watchJob = scope.launch {
            SessionHolder.state
                .map { it.running }
                .distinctUntilChanged()
                .collect { render() }
        }
    }

    override fun onStopListening() {
        watchJob?.cancel()
        watchJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = SessionHolder.state.value
        if (state.running) {
            MonitorStarter.action(this, MonitorService.ACTION_STOP)
        } else {
            // 一键开始：不带 route extras，由服务在 IO 协程内读「上次路线」（不违反硬性规则 7）。
            // QS 磁贴不在后台启动 FGS 的官方豁免清单内 → 失败则拉起 App 界面再启动（禁止静默失败）。
            MonitorStarter.start(this, emptyMap()) { err ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_AUTO_START, true)
                    putExtra(MainActivity.EXTRA_START_ERROR, err)
                }
                startActivityAndCollapseCompat(intent)
            }
        }
        // 乐观刷新一次（让用户立刻看到反馈）；真实状态由上面的订阅在翻转时刷新
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val running = SessionHolder.state.value.running
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) getString(R.string.tile_sub_running) else getString(R.string.tile_sub_idle)
        }
        runCatching { tile.updateTile() }
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                3001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
