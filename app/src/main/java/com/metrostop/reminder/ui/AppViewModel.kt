package com.metrostop.reminder.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrostop.reminder.core.model.MonitorUiState
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.route.LineRepository
import com.metrostop.reminder.platform.data.LogsCleaner
import com.metrostop.reminder.platform.data.SettingsStore
import com.metrostop.reminder.platform.service.MonitorService
import com.metrostop.reminder.platform.service.MonitorStarter
import com.metrostop.reminder.platform.session.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 路线选择的三级下拉状态 */
data class RouteSelection(
    val lineId: String? = null,
    val directionId: String? = null,
    val boardingId: String? = null,
    val destinationId: String? = null,
)

class AppViewModel(private val appContext: Context) : ViewModel() {

    private val settings = SettingsStore(appContext)

    private val _repo = MutableStateFlow(LineRepository.empty())
    val repo: StateFlow<LineRepository> = _repo.asStateFlow()

    private val _selection = MutableStateFlow(RouteSelection())
    val selection: StateFlow<RouteSelection> = _selection.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _alertVibOnly = MutableStateFlow(false)
    val alertVibOnly: StateFlow<Boolean> = _alertVibOnly.asStateFlow()

    /** 新装默认关（csv-storage-policy）；老用户由 init 里的一次性迁移显式写回 true */
    private val _recordCsv = MutableStateFlow(false)
    val recordCsv: StateFlow<Boolean> = _recordCsv.asStateFlow()

    private val _debugExpanded = MutableStateFlow(false)
    val debugExpanded: StateFlow<Boolean> = _debugExpanded.asStateFlow()

    private val _keepAliveDone = MutableStateFlow(false)
    val keepAliveDone: StateFlow<Boolean> = _keepAliveDone.asStateFlow()

    private val _replayReport = MutableStateFlow<String?>(null)
    val replayReport: StateFlow<String?> = _replayReport.asStateFlow()

    /** logs 目录占用（设置卡片显示）；null = 还没扫过 */
    private val _logsUsage = MutableStateFlow<String?>(null)
    val logsUsage: StateFlow<String?> = _logsUsage.asStateFlow()

    /** 服务写入的状态（唯一来源） */
    val monitorState: StateFlow<MonitorUiState> = SessionHolder.state

    init {
        viewModelScope.launch {
            // 老用户迁移（默认值 true → false 的兼容）：先落成显式值再 collect，避免开关显示错位
            runCatching { settings.migrateRecordCsvIfNeeded() }
            loadRoutes()
            settings.lastRoute.collect { saved ->
                if (saved.lineId != null && _selection.value.lineId == null) {
                    restore(saved.lineId, saved.directionId, saved.boardingId, saved.destinationId)
                }
            }
        }
        viewModelScope.launch {
            settings.alertMode.collect { _alertVibOnly.value = it == SettingsStore.MODE_VIB_ONLY }
        }
        viewModelScope.launch {
            settings.recordCsv.collect {
                _recordCsv.value = it
                refreshLogsUsage()
            }
        }
        viewModelScope.launch { settings.debugExpanded.collect { _debugExpanded.value = it } }
        viewModelScope.launch { settings.keepAliveGuideDone.collect { _keepAliveDone.value = it } }
    }

    private suspend fun loadRoutes() = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            val text = appContext.assets.open("subway_lines.json").bufferedReader().use { it.readText() }
            LineRepository.parse(text).getOrNull()
        }.getOrNull()
        if (parsed == null) {
            _uiError.value = "线路数据加载失败"
        } else {
            _repo.value = parsed
        }
    }

    private fun restore(lineId: String, dirId: String?, boardId: String?, destId: String?) {
        val r = _repo.value
        if (r.line(lineId) == null) return
        val sel = RouteSelection(lineId, dirId, boardId, destId)
        _selection.value = sel
    }

    // ---------------- 选择（每一级都要重置下游）----------------

    fun selectLine(lineId: String) {
        _selection.value = RouteSelection(lineId = lineId)
    }

    fun selectDirection(dirId: String) {
        _selection.value = _selection.value.copy(directionId = dirId, boardingId = null, destinationId = null)
    }

    fun selectBoarding(stationId: String) {
        _selection.value = _selection.value.copy(boardingId = stationId, destinationId = null)
    }

    fun selectDestination(stationId: String) {
        _selection.value = _selection.value.copy(destinationId = stationId)
    }

    /** 当前选择构成的合法路线（无则 null） */
    fun currentRoute(): RouteSpec? {
        val s = _selection.value
        val l = s.lineId ?: return null
        val d = s.directionId ?: return null
        val b = s.boardingId ?: return null
        val e = s.destinationId ?: return null
        return _repo.value.buildRoute(l, d, b, e)
    }

    // ---------------- 控制 ----------------

    fun start() {
        val route = currentRoute() ?: run {
            _uiError.value = "请先完成路线选择"
            return
        }
        viewModelScope.launch { settings.saveRoute(route) }
        MonitorStarter.start(
            appContext,
            mapOf(
                MonitorService.EXTRA_LINE to route.lineId,
                MonitorService.EXTRA_DIRECTION to route.directionId,
                MonitorService.EXTRA_BOARDING to route.boardingStation.id,
                MonitorService.EXTRA_DESTINATION to route.destinationStation.id,
            ),
        ) { err -> _uiError.value = "启动失败：$err" }
    }

    /** 磁贴拉起时用「上次路线」启动（App 可见 → 前台启动合法） */
    fun startWithLastRoute(onFail: (String?) -> Unit) {
        viewModelScope.launch {
            val last = runCatching { settings.lastRoute.first() }.getOrNull()
            val route = last?.let {
                val l = it.lineId ?: return@let null
                val d = it.directionId ?: return@let null
                val b = it.boardingId ?: return@let null
                val e = it.destinationId ?: return@let null
                _repo.value.buildRoute(l, d, b, e)
            }
            if (route == null) {
                onFail("还没有上次路线，请先选择路线")
                return@launch
            }
            MonitorStarter.start(
                appContext,
                mapOf(
                    MonitorService.EXTRA_LINE to route.lineId,
                    MonitorService.EXTRA_DIRECTION to route.directionId,
                    MonitorService.EXTRA_BOARDING to route.boardingStation.id,
                    MonitorService.EXTRA_DESTINATION to route.destinationStation.id,
                ),
                onFail,
            )
        }
    }

    fun stop() = MonitorStarter.action(appContext, MonitorService.ACTION_STOP)

    fun correctUp() = MonitorStarter.action(appContext, MonitorService.ACTION_CORRECT_UP)

    fun correctDown() = MonitorStarter.action(appContext, MonitorService.ACTION_CORRECT_DOWN)

    fun testAlert() = MonitorStarter.action(appContext, MonitorService.ACTION_TEST_ALERT)

    // ---------------- CSV / 回放 ----------------

    /** logs 目录下的 sensor CSV，按时间倒序 */
    suspend fun listSensorCsv(): List<File> = withContext(Dispatchers.IO) {
        val dir = File(appContext.getExternalFilesDir("logs")?.absolutePath ?: return@withContext emptyList())
        (dir.listFiles { f -> f.isFile && f.name.startsWith("sensor_") && f.name.endsWith(".csv") } ?: emptyArray())
            .sortedByDescending { it.name }
    }

    fun logsDir(): String =
        appContext.getExternalFilesDir("logs")?.absolutePath ?: appContext.filesDir.absolutePath

    /** 离线回放指定 CSV（用当前选择的路线；服务端读路线若缺失则回退上次路线） */
    fun replay(file: File) {
        val route = currentRoute()
        val extras = mutableMapOf(MonitorService.EXTRA_PATH to file.absolutePath)
        if (route != null) {
            extras[MonitorService.EXTRA_LINE] = route.lineId
            extras[MonitorService.EXTRA_DIRECTION] = route.directionId
            extras[MonitorService.EXTRA_BOARDING] = route.boardingStation.id
            extras[MonitorService.EXTRA_DESTINATION] = route.destinationStation.id
        }
        _replayReport.value = "回放中…"
        MonitorStarter.replay(appContext, extras) { err -> _replayReport.value = "回放启动失败：$err" }
    }

    fun loadReplayReport() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(logsDir(), "replay_report.txt").readText() }.getOrNull()
            }
            _replayReport.value = text ?: "暂无回放报告"
        }
    }

    // ---------------- 设置 ----------------

    fun setAlertVibOnly(vibOnly: Boolean) {
        _alertVibOnly.value = vibOnly
        viewModelScope.launch {
            settings.setAlertMode(if (vibOnly) SettingsStore.MODE_VIB_ONLY else SettingsStore.MODE_SOUND_VIB)
        }
    }

    fun setRecordCsv(enabled: Boolean) {
        _recordCsv.value = enabled
        viewModelScope.launch { settings.setRecordCsv(enabled) }
    }

    /** 扫描 logs 占用（设置卡片 / 调试面板显示）；IO 线程 */
    fun refreshLogsUsage() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { LogsCleaner(appContext).scan().readable() }.getOrNull()
            }
            _logsUsage.value = text
        }
    }

    /**
     * 手动「立即清理日志」：删掉全部已完成会话，保留 `replay_report.txt`。
     * 正在录制时保护当前会话（按服务暴露的 csvDir 会话名判断）。
     */
    fun cleanLogsNow() {
        viewModelScope.launch {
            val active = withContext(Dispatchers.IO) {
                // 正在录制时，CsvRecorder 的会话名 = 最新 sensor CSV 的 stamp（由服务持有）
                // 这里只保护「监测中」的会话，避免删到正在写的文件
                runCatching {
                    val dir = appContext.getExternalFilesDir("logs") ?: return@runCatching null
                    if (!MonitorService.isRunning) return@runCatching null
                    dir.listFiles { f -> f.isFile && f.name.startsWith("sensor_") && f.name.endsWith(".csv") }
                        ?.maxByOrNull { it.name }
                        ?.name?.removePrefix("sensor_")?.removeSuffix(".csv")
                }.getOrNull()
            }
            val deleted = withContext(Dispatchers.IO) {
                runCatching { LogsCleaner(appContext).cleanAll(exceptStamp = active) }.getOrDefault(0)
            }
            refreshLogsUsage()
            _uiError.value = "已清理 $deleted 次会话的 CSV 日志"
        }
    }

    fun setDebugExpanded(expanded: Boolean) {
        _debugExpanded.value = expanded
        viewModelScope.launch { settings.setDebugExpanded(expanded) }
    }

    fun setKeepAliveDone(done: Boolean) {
        _keepAliveDone.value = done
        viewModelScope.launch { settings.setKeepAliveGuideDone(done) }
    }

    fun reportError(message: String) {
        _uiError.value = message
    }

    fun clearError() {
        _uiError.value = null
    }
}
