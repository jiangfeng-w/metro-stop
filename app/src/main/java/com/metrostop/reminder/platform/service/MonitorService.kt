package com.metrostop.reminder.platform.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.metrostop.reminder.R
import com.metrostop.reminder.core.feature.FeatureExtractor
import com.metrostop.reminder.core.fsm.MonitorSession
import com.metrostop.reminder.core.fsm.TickOutcome
import com.metrostop.reminder.core.model.Features
import com.metrostop.reminder.core.model.MonitorUiState
import com.metrostop.reminder.core.model.MotionSample
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig
import com.metrostop.reminder.core.model.EndReason
import com.metrostop.reminder.core.replay.CsvReplay
import com.metrostop.reminder.core.route.LineRepository
import com.metrostop.reminder.platform.data.CsvRecorder
import com.metrostop.reminder.platform.data.SettingsStore
import com.metrostop.reminder.platform.notify.Notifier
import com.metrostop.reminder.platform.notify.Notifications
import com.metrostop.reminder.platform.power.WakeLockGuard
import com.metrostop.reminder.platform.sensor.SensorCollector
import com.metrostop.reminder.platform.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台服务（硬性规则 5）：`specialUse` + PARTIAL_WAKE_LOCK，50 Hz 采集 → 特征 → 会话 → 通知。
 *
 * - `onStartCommand` **首行** startForeground（5 秒红线）；
 * - 从后台启动的合法性由调用方（磁贴 / 接收器）catch，本类只保证不崩；
 * - 状态唯一出口：写 SessionHolder（UI / 磁贴只读）。
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.metrostop.reminder.action.START_MONITOR"
        const val ACTION_STOP = "com.metrostop.reminder.action.STOP_MONITOR"
        const val ACTION_CORRECT_UP = "com.metrostop.reminder.action.CORRECT_UP"
        const val ACTION_CORRECT_DOWN = "com.metrostop.reminder.action.CORRECT_DOWN"
        const val ACTION_TEST_ALERT = "com.metrostop.reminder.action.TEST_ALERT"
        const val ACTION_REPLAY = "com.metrostop.reminder.action.REPLAY"

        const val EXTRA_LINE = "line_id"
        const val EXTRA_DIRECTION = "direction_id"
        const val EXTRA_BOARDING = "boarding_id"
        const val EXTRA_DESTINATION = "destination_id"
        const val EXTRA_PATH = "path"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore
    private lateinit var notifier: Notifier
    private lateinit var wakeLock: WakeLockGuard

    /** 通知按钮接收器：动态注册，RECEIVER_NOT_EXPORTED（硬性规则 6） */
    private val actionReceiver = MonitorActionReceiver()
    private var receiverRegistered = false

    // 以下字段跨线程访问（主线程写、传感器 HandlerThread 读）→ @Volatile
    @Volatile
    private var session: MonitorSession? = null

    @Volatile
    private var extractor: FeatureExtractor? = null

    @Volatile
    private var recorder: CsvRecorder? = null

    private var collector: SensorCollector? = null

    private var uiTickJob: Job? = null
    private var latestVib = 0f
    private var latestH = 0f
    private var lastOngoingMs = 0L
    private var silentMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        notifier = Notifier(context = this, alertSilent = { silentMode })
        wakeLock = WakeLockGuard(this)
        Notifications.ensureChannels(this)
        preloadSettings()
    }

    private var recordCsvEnabled = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 硬性规则 5：首行必须 startForeground（5 秒红线）
        val idle = notifier.buildOngoing(SessionHolder.state.value.copy(running = false))
        startForegroundCompat(idle)

        when (intent?.action) {
            // 这两个动作会自行管理生命周期（且可能是异步的：磁贴路径需先读「上次路线」），
            // **不能**在 onStartCommand 末尾按 isRunning 判断退出 —— 否则会当场自杀。
            ACTION_START -> startMonitoring(intent)
            ACTION_REPLAY -> startReplay(intent)

            ACTION_STOP -> {
                stopMonitoring(EndReason.MANUAL)
                return START_NOT_STICKY
            }

            ACTION_CORRECT_UP -> {
                correct(up = true)
                exitIfIdle()
            }

            ACTION_CORRECT_DOWN -> {
                correct(up = false)
                exitIfIdle()
            }

            ACTION_TEST_ALERT -> {
                notifier.notifyTest()
                exitIfIdle()
            }

            else -> exitIfIdle()
        }
        return START_NOT_STICKY
    }

    /** 瞬时动作处理完即退出，避免前台服务与「未在监测」常驻通知滞留 */
    private fun exitIfIdle() {
        if (!isRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- 监测

    private fun startMonitoring(intent: Intent) {
        if (isRunning) return
        val lineId = intent.getStringExtra(EXTRA_LINE)
        val dirId = intent.getStringExtra(EXTRA_DIRECTION)
        val boardId = intent.getStringExtra(EXTRA_BOARDING)
        val destId = intent.getStringExtra(EXTRA_DESTINATION)
        if (lineId != null && dirId != null && boardId != null && destId != null) {
            beginMonitoring(lineId, dirId, boardId, destId)
            return
        }
        // 磁贴 / 通知按钮：无 route extras → 由服务自己读「上次路线」（IO 协程内读 DataStore，
        // 硬性规则 7：磁贴内不得在主线程读 DataStore）
        scope.launch {
            val last = runCatching { settings.lastRoute.first() }.getOrNull()
            val line = last?.lineId
            val dir = last?.directionId
            val board = last?.boardingId
            val dest = last?.destinationId
            if (line == null || dir == null || board == null || dest == null) {
                main.post { failAndStop(getString(R.string.err_no_last_route)) }
            } else {
                main.post { beginMonitoring(line, dir, board, dest) }
            }
        }
    }

    private fun beginMonitoring(lineId: String, dirId: String, boardId: String, destId: String) {
        if (isRunning) return
        val repo = loadRoutes()
        val route: RouteSpec? = repo?.buildRoute(lineId, dirId, boardId, destId)
        if (route == null) {
            failAndStop(getString(R.string.err_bad_route))
            return
        }

        val config = TuningConfig.Default
        val s = MonitorSession(route, config)
        session = s
        extractor = FeatureExtractor(config)

        val now = SystemClock.elapsedRealtime()
        handleOutcome(s.start(now), notify = false, fromReplay = false)

        // CSV 录制：**同步创建**（元数据已在 preloadSettings 中读好），
        // 避免此前「协程异步创建 → 磁贴短会话结束时会话还没建立起 recorder」导致 0 字节文件。
        if (recordCsvEnabled && recorder == null) {
            val rec = CsvRecorder(this, route, config, scope)
            rec.start()
            recorder = rec
        }

        // 传感器
        val c = SensorCollector(this) { sample -> onSensorSample(sample) }
        if (!c.start()) {
            c.stop() // 释放 HandlerThread（start 失败时 registerListener 未成功，线程仍在）
            failAndStop(getString(R.string.err_no_sensor))
            return
        }
        collector = c

        wakeLock.acquire(timeoutMs = ((config.maxMonitorMin + 5) * 60_000).toLong())
        registerActionReceiver()
        isRunning = true
        updateOngoing()
        startUiTicker()
    }

    /** 设置项预加载：服务启动时同步读一次（onCreate 非主线程敏感路径，DataStore 首读很快） */
    private fun preloadSettings() {
        runCatching {
            silentMode = kotlinx.coroutines.runBlocking { settings.alertMode.first() } == SettingsStore.MODE_VIB_ONLY
            recordCsvEnabled = kotlinx.coroutines.runBlocking { settings.recordCsv.first() }
        }
    }

    private fun registerActionReceiver() {
        if (receiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(Notifier.ACTION_STOP)
            addAction(Notifier.ACTION_MISSED_ONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterActionReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(actionReceiver) }
        receiverRegistered = false
    }

    private fun onSensorSample(sample: MotionSample) {
        val s = session ?: return
        val ex = extractor ?: return
        val f = ex.process(sample)
        latestH = f.h
        latestVib = f.vib

        val outcome = s.tick(Features(sample.tMs, latestH, latestVib))
        recorder?.onSample(sample, latestVib, latestH, s.state, s.stationCount)
        handleOutcome(outcome, notify = true, fromReplay = false)

        // 状态栏刷新节流（1 s），避免 50 Hz 更新通知
        if (sample.tMs - lastOngoingMs >= 1000L) {
            lastOngoingMs = sample.tMs
            updateOngoing()
        }
    }

    private fun handleOutcome(outcome: TickOutcome, notify: Boolean, fromReplay: Boolean) {
        val s = session
        for (e in outcome.events) {
            recorder?.onEvent(e)
            if (notify && s != null) {
                notifier.notifyEvent(e, s.snapshot(SystemClock.elapsedRealtime()))
            }
        }
        if (outcome.endRequested) {
            stopMonitoring(outcome.endReason ?: EndReason.MANUAL)
        }
    }

    private fun updateOngoing() {
        // 已结束（stopSelf / 自动结束）后不再回贴通知：
        // 否则 1 s ticker 或延迟回调会把常驻通知重新贴出来，留下「假监测」残留。
        if (!isRunning) return
        val s = session ?: return
        val state = s.snapshot(SystemClock.elapsedRealtime()).copy(
            running = true,
            vib = latestVib,
            h = latestH,
            recording = recorder != null,
            csvDir = recorder?.logsDir,
            measuredHz = collector?.measuredHz ?: 0.0,
            usingLinearSensor = collector?.usingLinearAcceleration ?: true,
        )
        SessionHolder.update(state)
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(Notifications.ID_ONGOING, notifier.buildOngoing(state))
        }
    }

    private fun startUiTicker() {
        uiTickJob?.cancel()
        uiTickJob = scope.launch {
            while (isRunning) {
                delay(1000)
                updateOngoing()
            }
        }
    }

    private fun correct(up: Boolean) {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        val outcome = if (up) s.correctUp(now) else s.correctDown(now)
        handleOutcome(outcome, notify = false, fromReplay = false)
        updateOngoing()
    }

    private fun stopMonitoring(reason: String) {
        val s = session
        if (s != null && s.running) {
            val outcome = s.finish(SystemClock.elapsedRealtime(), reason)
            for (e in outcome.events) recorder?.onEvent(e)
        }
        teardown(clearState = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 启动失败：**必须给出可见反馈**（硬性规则 5：禁止静默失败）。
     * 磁贴 / 通知按钮路径下 App 可能不在前台，因此除了 SessionHolder 之外还发一条通知。
     */
    private fun failAndStop(error: String) {
        teardown(clearState = false)
        SessionHolder.update(MonitorUiState(error = error))
        notifier.notifyError(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun teardown(clearState: Boolean) {
        isRunning = false
        uiTickJob?.cancel()
        uiTickJob = null
        unregisterActionReceiver()
        collector?.stop()
        collector = null
        wakeLock.release()
        val rec = recorder
        recorder = null
        // CSV 收尾（关闭队列 + 等写盘）放 IO 线程，避免阻塞主线程；
        // CsvRecorder 用独立 scope，服务 scope 取消不影响落盘。
        if (rec != null) {
            // 后台收尾，onDestroy 还会再等一次（幂等）
            Thread {
                runCatching { rec.stop() }
            }.apply { isDaemon = true }.start()
        }
        // 主动撤销常驻通知：stopForeground 之后 ticker / 延迟回调可能已把它重新贴出
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(Notifications.ID_ONGOING)
        }
        if (clearState) {
            SessionHolder.clear()
        } else {
            SessionHolder.update(MonitorUiState())
        }
    }

    // ---------------------------------------------------------------- 回放

    /**
     * 离线回放（M1 验收必需）：读 sensor CSV → 与现场完全相同的提取 / 会话代码 → 事件序列。
     * 结果写 `logs/replay_report.txt` 并显示在调试面板（lastEvent）。
     */
    private fun startReplay(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH)
        val lineId = intent.getStringExtra(EXTRA_LINE)
        val dirId = intent.getStringExtra(EXTRA_DIRECTION)
        val boardId = intent.getStringExtra(EXTRA_BOARDING)
        val destId = intent.getStringExtra(EXTRA_DESTINATION)
        if (path != null && (lineId == null || dirId == null || boardId == null || destId == null)) {
            // 只给了路径：回放用当前选中路线 → 读上次路线
            scope.launch {
                val last = runCatching { settings.lastRoute.first() }.getOrNull()
                val l = last?.lineId
                val d = last?.directionId
                val b = last?.boardingId
                val e = last?.destinationId
                if (l == null || d == null || b == null || e == null) {
                    main.post { failAndStop(getString(R.string.err_no_last_route)) }
                } else {
                    main.post { doReplay(path, l, d, b, e) }
                }
            }
            return
        }
        if (path == null || lineId == null || dirId == null || boardId == null || destId == null) {
            failAndStop(getString(R.string.err_replay_params))
            return
        }
        doReplay(path, lineId, dirId, boardId, destId)
    }

    private fun doReplay(path: String, lineId: String, dirId: String, boardId: String, destId: String) {
        val route = loadRoutes()?.buildRoute(lineId, dirId, boardId, destId)
        if (route == null) {
            failAndStop(getString(R.string.err_bad_route))
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val file = File(path)
                require(file.isFile) { "文件不存在：$path" }
                CsvReplay.replay(file.readText(), route)
            }
            main.post {
                result.fold(
                    onSuccess = { r ->
                        val report = buildString(256) {
                            append("回放文件：").append(File(path).name).append('\n')
                            append("样本数：").append(r.samples).append('\n')
                            append("识别站数：").append(r.finalStationCount).append(" / 应到 ").append(route.stopCount).append('\n')
                            append("事件序列：\n")
                            r.comparableLines().forEach { append(it).append('\n') }
                        }
                        writeReplayReport(report)
                        SessionHolder.update(
                            MonitorUiState(
                                running = false,
                                lastEvent = getString(R.string.replay_done_hint, r.finalStationCount, r.samples),
                                lastEventMs = System.currentTimeMillis(),
                                csvDir = File(path).parent,
                            ),
                        )
                    },
                    onFailure = { e ->
                        SessionHolder.update(MonitorUiState(error = getString(R.string.err_replay_failed, e.message ?: "")))
                    },
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun writeReplayReport(text: String) {
        runCatching {
            val dir = getExternalFilesDir("logs") ?: filesDir
            dir.mkdirs()
            File(dir, "replay_report.txt").writeText(text)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun loadRoutes(): LineRepository? = runCatching {
        val text = assets.open("subway_lines.json").bufferedReader().use { it.readText() }
        LineRepository.parse(text).getOrNull()
    }.getOrNull()

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_ONGOING, n)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务销毁前同步收尾 CSV：teardown 里的后台线程可能在进程回收前来不及执行，
        // 这里再等一次（stop 幂等，重复调用安全）。
        val rec = recorder
        recorder = null
        if (rec != null) {
            // 主线程：短超时（500 ms）等收尾，避免 ANR；常规情况下 teardown 已先一步收好
            runCatching { rec.stop(timeoutMs = 500) }
        }
        isRunning = false
        uiTickJob?.cancel()
        collector?.stop()
        collector = null
        wakeLock.release()
        unregisterActionReceiver()
        scope.cancel()
        // 注意：**不要**在这里 clear()：teardown 已把状态收拾好，
        // 而回放结果（lastEvent / csvDir）需要留给 UI 显示，不能被服务销毁抹掉。
    }
}

/**
 * 从后台启动前台服务的统一入口（硬性规则 5：必须 catch ForegroundServiceStartNotAllowedException
 * 并给出可见反馈，禁止静默失败）。
 */
object MonitorStarter {

    fun start(context: Context, extras: Map<String, String>, onFail: (String) -> Unit = {}) {
        val intent = Intent(context, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        send(context, intent, onFail)
    }

    fun replay(context: Context, extras: Map<String, String>, onFail: (String) -> Unit = {}) {
        val intent = Intent(context, MonitorService::class.java).apply {
            action = MonitorService.ACTION_REPLAY
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        send(context, intent, onFail)
    }

    fun action(context: Context, actionName: String, onFail: (String) -> Unit = {}) {
        send(context, Intent(context, MonitorService::class.java).setAction(actionName), onFail)
    }

    private fun send(context: Context, intent: Intent, onFail: (String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException 等
            onFail(e.message ?: e.javaClass.simpleName)
        }
    }
}
