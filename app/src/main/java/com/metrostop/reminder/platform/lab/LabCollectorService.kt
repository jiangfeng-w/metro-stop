package com.metrostop.reminder.platform.lab

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.metrostop.reminder.platform.notify.VibratorHelper

/**
 * 实验室数据采集前台服务（lab-data-collection，**旁路调试工具，与监测主链路零交集**）：
 *
 * - 独立 `specialUse` 前台服务（subtype=lab_collection），首行 startForeground（硬性规则 5 同款）；
 * - 与 MonitorService 完全并行互不干扰：不碰 SessionHolder、不碰监测 CSV、不碰算法；
 * - 数据面见 `docs/spec/active/lab-data-collection/需求.md` 第五节：imu(accel/lin/gyro/mag/rot) /
 *   baro / light(prox) / loc / gnss / steps / cell / events；
 * - 权限逐项降级：被拒的流不开，其余照常；「拒绝即静默不录」只发生在调试采集，不影响主功能；
 * - 定位回调在切后台 30 s 后会被系统自动停（回调静默失效）→ onDestroy 时必须主动
 *   removeUpdates；前台服务 + wakelock 尽力维持，HyperOS 若仍杀回调，数据里正好留下证据。
 */
class LabCollectorService : Service() {

    companion object {
        const val ACTION_START = "com.metrostop.reminder.action.LAB_START"
        const val ACTION_STOP = "com.metrostop.reminder.action.LAB_STOP_COLLECT"
        const val ACTION_MARK = "com.metrostop.reminder.action.LAB_MARK"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    /** 传感器 / GNSS 回调线程（不占主线程，也避免与监测的 SensorCollector 抢线程） */
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    private var recorder: LabRecorder? = null
    private lateinit var vibrator: VibratorHelper
    private lateinit var wakeLock: com.metrostop.reminder.platform.power.WakeLockGuard

    // 通知按钮用 PendingIntent.getService 直达本服务（无后台启动问题），
    // 不再需要 broadcast 接收器（首版 LabActionReceiver 已删除，2026-09-27）。

    // ---- 各采集器句柄（stop 时逐个注销）----
    private var sensorManager: android.hardware.SensorManager? = null
    private val sensorListener = LabSensorListener { stream, row -> recorder?.write(stream, row) }
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var telephony: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var lastGnssRowMs = 0L

    /** 📍 标记次数（通知文案用） */
    private var marks = 0
    private var startedElapsedMs = 0L

    /** 各场景已标记次数（UI 徽标） */
    private val marksByScenario = LinkedHashMap<String, Int>()

    /** 当前场景（= 最近一次带场景名的标记；null = 尚未标记 / 仅兜底标记） */
    @Volatile
    private var currentScenario: String? = null

    /** 状态里展示的流（先按计划值，传感器注册回调后按实际结果修正） */
    @Volatile
    private var activeStreams: List<String> = emptyList()

    // ---------------------------------------------------------------- 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LabNotifications.ensureChannel(this)
        vibrator = VibratorHelper(this)
        wakeLock = com.metrostop.reminder.platform.power.WakeLockGuard(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 首行 startForeground（5 秒红线）
        startForegroundCompat(buildOngoing())
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                startCollecting()
            }

            ACTION_STOP -> {
                if (isRunning) {
                    stopCollecting()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }

            ACTION_MARK -> {
                if (isRunning) {
                    onMark(intent.getStringExtra(LabStarter.EXTRA_SCENARIO))
                    if (!isRunning) { // mark 不会停服务，防御性处理
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }

            else -> {
                if (!isRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 采集启停

    private fun startCollecting() {
        val store = LabStore(this)
        val stamp = store.newStamp()

        // ---- 权限矩阵：逐项判、拒绝即降级（不开该流）----
        val hasLoc = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val hasActivity = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        val hasPhone = hasPermission(Manifest.permission.READ_PHONE_STATE)

        // 想开的传感器流（无需权限的永远在）
        val wantedSensors = buildList {
            addAll(listOf("accel", "lin", "gyro", "mag", "rot", "baro", "light"))
            if (hasActivity) addAll(listOf("step_detector", "step_counter"))
        }
        val wantedFlows = buildList {
            add("imu"); add("baro"); add("light"); add("events")
            if (hasActivity) add("steps")
            if (hasLoc) { add("loc"); add("gnss") }
            if (hasPhone) add("cell")
        }

        val headers = mapOf(
            "imu" to "t_ms,utc_ms,stream,ax,ay,az",
            "baro" to "t_ms,utc_ms,pressure,alt_m",
            "light" to "t_ms,utc_ms,light_lx,prox",
            "loc" to "t_ms,utc_ms,lat,lon,accuracy,speed,bearing,altitude,provider",
            "gnss" to "t_ms,utc_ms,sat_total,sat_used,cn0_mean,cn0_max",
            "steps" to "t_ms,utc_ms,detector,counter_total",
            "cell" to "t_ms,utc_ms,dbm,level",
            "events" to "t_ms,utc_ms,type,detail",
        )

        activeStreams = wantedFlows.toMutableList()
        val rec = LabRecorder(this, stamp)
        rec.start(headers)
        rec.writeEvent("COLLECT_START", "wanted_flows=${wantedFlows.joinToString("|")};permission_loc=$hasLoc,activity=$hasActivity,phone=$hasPhone")
        recorder = rec

        // 独立保留策略清理（IO 线程；activeStamp 保护本次）
        Thread {
            runCatching { LabStore(this).clean(activeStamp = stamp) }
        }.apply { isDaemon = true }.start()

        // ---- 传感器注册（全部挂 sensorHandler 线程）----
        sensorThread = HandlerThread("LabCollector").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        // onReady 里回填「实际注册成功」的流（降级证据写入事件流）
        sensorListener.start(sensorManager!!, sensorHandler) { actuallyOn ->
            recorder?.writeEvent("SENSORS_ON", actuallyOn.joinToString("|"))
            val missing = wantedSensors.filter { it !in actuallyOn }
            if (missing.isNotEmpty()) {
                recorder?.writeEvent("SENSORS_MISSING", missing.joinToString("|"))
            }
            // 传感器支撑的文件流按实际注册情况修正；权限流维持计划值
            val sensorBacked = buildList {
                if (actuallyOn.any { it in setOf("accel", "lin", "gyro", "mag", "rot") }) add("imu")
                if ("baro" in actuallyOn) add("baro")
                if (actuallyOn.any { it == "light" || it == "prox" }) add("light")
                if (hasActivity && actuallyOn.any { it.startsWith("step") }) add("steps")
            }
            val plannedNonSensor = wantedFlows.filter { it !in setOf("imu", "baro", "light", "steps") }
            activeStreams = sensorBacked + plannedNonSensor
            updateState()
        }

        // ---- 定位 ----
        if (hasLoc) startLocation()
        // ---- 蜂窝 ----
        if (hasPhone) startCell()

        // ---- 前台服务配套 ----
        wakeLock.acquire(timeoutMs = 4L * 3600_000L) // 采集上限 4 小时
        isRunning = true
        startedElapsedMs = SystemClock.elapsedRealtime()
        marks = 0
        marksByScenario.clear()
        currentScenario = null
        notifyOngoing()
        updateState()
    }

    private fun stopCollecting() {
        isRunning = false
        sensorListener.stop()
        stopLocation()
        stopCell()
        wakeLock.release()
        val rec = recorder
        recorder = null
        // 落盘收尾放后台线程（stop 幂等；onDestroy 再兜底一次）
        Thread {
            runCatching { rec?.stop() }
        }.apply { isDaemon = true }.start()
        runCatching {
            sensorThread.quitSafely()
        }
        runCatching { notifyCancel() }
        LabHolder.update(LabHolder.State(running = false, stamp = rec?.baseName, marks = marks))
    }

    override fun onDestroy() {
        if (isRunning) stopCollecting()
        val rec = recorder
        recorder = null
        if (rec != null) runCatching { rec.stop(timeoutMs = 500) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 📍标记

    /**
     * 场景标记。语义 =「**进入**该场景」：两次标记之间归上一个场景（无需起止配对）。
     *
     * @param scenarioId 场景 id（[com.metrostop.reminder.core.lab.LabScenarios.Scenario.id]）；
     *   null / 未知 id（通知兜底按钮）落 `generic`。
     */
    private fun onMark(scenarioId: String?) {
        marks++
        val id = scenarioId?.takeIf { com.metrostop.reminder.core.lab.LabScenarios.byId.containsKey(it) }
            ?: com.metrostop.reminder.core.lab.LabScenarios.GENERIC_MARK
        marksByScenario[id] = (marksByScenario[id] ?: 0) + 1
        if (id != com.metrostop.reminder.core.lab.LabScenarios.GENERIC_MARK) currentScenario = id
        val name = com.metrostop.reminder.core.lab.LabScenarios.byId[id]?.name ?: "未指定场景"
        val now = SystemClock.elapsedRealtime()
        recorder?.writeEvent("MARK", "#$marks $id $name")
        runCatching { vibrator.vibrateShort() }
        notifyOngoing()
        updateState(lastMarkMs = now)
    }

    // ---------------------------------------------------------------- 定位 / GNSS / 蜂窝

    private fun startLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val listener = LocationListener { loc: Location ->
            // 回调线程（系统 binder）；只拼行入队
            val row = buildString(128) {
                append(SystemClock.elapsedRealtime()).append(',')
                append(System.currentTimeMillis()).append(',')
                append(String.format(java.util.Locale.US, "%.6f,%.6f,%.1f,%.2f,%.1f,%.1f,%s",
                    loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.bearing, loc.altitude,
                    loc.provider ?: ""))
            }
            recorder?.write("loc", row)
        }
        locationListener = listener
        val ok = runCatching {
            // GPS + network 双 provider 各自监听；1s 一次（采集需求，允许高频）
            val looper = sensorHandler.looper
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, looper)
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, looper)
            }
            true
        }.getOrDefault(false)
        recorder?.writeEvent("LOC_START", if (ok) "gps+network@1s" else "request_failed")

        // GNSS 状态：卫星数 / 信噪比（≥1s 节流写行）
        if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastGnssRowMs < 1000L) return
                lastGnssRowMs = now
                var used = 0
                var sum = 0.0
                var max = 0.0
                val n = status.satelliteCount
                for (i in 0 until n) {
                    val cn0: Double = status.getCn0DbHz(i).toDouble()
                    sum += cn0
                    if (cn0 > max) max = cn0
                    if (status.usedInFix(i)) used++
                }
                val mean = if (n > 0) sum / n else 0.0
                val row = "$now,${System.currentTimeMillis()},$n,$used," +
                    String.format(java.util.Locale.US, "%.1f,%.1f", mean, max)
                recorder?.write("gnss", row)
            }
        }
            gnssCallback = cb
            runCatching { lm.registerGnssStatusCallback(cb, sensorHandler) }
            recorder?.writeEvent("GNSS_START", "callback_registered")
        } else {
            recorder?.writeEvent("GNSS_START", "gps_provider_disabled")
        }
    }

    private fun stopLocation() {
        val lm = locationManager
        val l = locationListener
        if (lm != null && l != null) {
            runCatching { lm.removeUpdates(l) }
        }
        val cb = gnssCallback
        if (lm != null && cb != null) {
            runCatching { lm.unregisterGnssStatusCallback(cb) }
        }
        locationListener = null
        gnssCallback = null
        locationManager = null
    }

    @Suppress("DEPRECATION")
    private fun startCell() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephony = tm
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                val s = signalStrength ?: return
                // 聚合 dbm 在 API 36 无公开方法：改为把各制式（GSM/NR/LTE/CDMA…）的 dbm 逐个落盘，
                // 分析时取主服务小区那列即可；level 0~4 直接可用
                val dbms = s.cellSignalStrengths
                    .mapNotNull { css -> runCatching { css.dbm }.getOrNull() }
                    .filter { it != Int.MAX_VALUE }
                val level: Int = try {
                    s.level
                } catch (_: Exception) {
                    -1
                }
                val row = "${SystemClock.elapsedRealtime()},${System.currentTimeMillis()}," +
                    "\"${dbms.joinToString(" ")}\",$level"
                recorder?.write("cell", row)
            }
        }
        phoneListener = listener
        runCatching { tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
        recorder?.writeEvent("CELL_START", "signal_strength_listener")
    }

    @Suppress("DEPRECATION")
    private fun stopCell() {
        val tm = telephony
        val l = phoneListener
        if (tm != null && l != null) {
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        telephony = null
        phoneListener = null
    }

    // ---------------------------------------------------------------- 杂项

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun buildOngoing(): Notification {
        val scenarioText = currentScenario?.let {
            com.metrostop.reminder.core.lab.LabScenarios.byId[it]?.name ?: it
        }
        return LabNotifications.buildOngoing(
            this,
            if (startedElapsedMs == 0L) SystemClock.elapsedRealtime() else startedElapsedMs,
            marks,
            scenarioText,
        )
    }

    private fun notifyOngoing() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(LabNotifications.ID_LAB, buildOngoing())
        }
    }

    private fun notifyCancel() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(LabNotifications.ID_LAB)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(LabNotifications.ID_LAB, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(LabNotifications.ID_LAB, n)
        }
    }

    private fun updateState(lastMarkMs: Long? = null) {
        val rec = recorder
        LabHolder.update(
            LabHolder.State(
                running = isRunning,
                stamp = rec?.baseName,
                activeStreams = activeStreams.toList(),
                marks = marks,
                marksByScenario = marksByScenario.toMap(),
                currentScenario = currentScenario,
                linesWritten = rec?.linesWritten ?: 0L,
                droppedLines = rec?.droppedLines() ?: 0,
                lastMarkAtMs = lastMarkMs ?: LabHolder.state.value.lastMarkAtMs,
            ),
        )
    }
}
