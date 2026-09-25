package com.metrostop.reminder.platform.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.retention.shouldMigrateRecordCsv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "metro_stop_settings")

/** 上次选择的路线（用于默认值） */
data class LastRoute(
    val lineId: String? = null,
    val directionId: String? = null,
    val boardingId: String? = null,
    val destinationId: String? = null,
)

/**
 * 本地设置（总纲 7.3）。**只能在 IO 协程里读写**；
 * 磁贴 / 小组件 / 接收器内不得在主线程读 DataStore（硬性规则 7）。
 */
class SettingsStore(private val context: Context) {

    val lastRoute: Flow<LastRoute> = context.dataStore.data.map { p ->
        LastRoute(
            lineId = p[K_LINE],
            directionId = p[K_DIR],
            boardingId = p[K_BOARD],
            destinationId = p[K_DEST],
        )
    }

    val alertMode: Flow<String> = context.dataStore.data.map { it[K_ALERT_MODE] ?: MODE_SOUND_VIB }

    /**
     * CSV 录制开关。**新装默认关**（csv-storage-policy：19 MB/小时无清理，日常无保留价值）。
     *
     * 老用户迁移见 [migrateRecordCsvIfNeeded]：已装机但没有该键的设备（= 从没拨过开关，
     * 按旧默认一直在录）会在此显式写入 `true`，避免升级后**静默停止录制**而丢掉标定数据。
     */
    val recordCsv: Flow<Boolean> = context.dataStore.data.map { it[K_RECORD_CSV] ?: false }
    val keepAliveGuideDone: Flow<Boolean> = context.dataStore.data.map { it[K_KEEPALIVE_DONE] ?: false }
    val firstRunDone: Flow<Boolean> = context.dataStore.data.map { it[K_FIRST_RUN_DONE] ?: false }
    val debugExpanded: Flow<Boolean> = context.dataStore.data.map { it[K_DEBUG_EXPANDED] ?: false }

    /**
     * 一次性迁移：把「老用户的隐式开启」落成显式值。
     *
     * 判据：**没有** `record_csv` 键（用户从没拨过开关 → 吃的是旧代码的 `?: true` 默认）
     * 但**有** `last_route_line_id` 键（说明装过并且用过，是真老用户，不是全新安装）。
     *
     * **必须靠 [K_RECORD_CSV_MIGRATED] 标记做成「只判定一次」**：判据里的 `last_route`
     * 键是用户会新写入的（选一次路线就有），若每次启动都重新判定，全新安装的用户
     * 选完路线后第二次打开 App 就会被误判成老用户、静默打开录制。
     * 首次调用即落标记：全新安装判定为「不需要迁移」后，后续启动不再判定。
     *
     * @return true = 本次真的发生了迁移（供日志 / 调试）
     */
    suspend fun migrateRecordCsvIfNeeded(): Boolean {
        var migrated = false
        context.dataStore.edit { p ->
            if (shouldMigrateRecordCsv(
                    hasMigratedMark = p[K_RECORD_CSV_MIGRATED] == true,
                    hasRecordCsvKey = p.contains(K_RECORD_CSV),
                    hasRouteKey = p.contains(K_LINE),
                )
            ) {
                p[K_RECORD_CSV] = true
                migrated = true
            }
            // 无论是否迁移都落标记：一次判定后不再重判（关键，见 K_RECORD_CSV_MIGRATED 注释）
            p[K_RECORD_CSV_MIGRATED] = true
        }
        return migrated
    }

    suspend fun saveRoute(route: RouteSpec) {
        context.dataStore.edit { p ->
            p[K_LINE] = route.lineId
            p[K_DIR] = route.directionId
            p[K_BOARD] = route.boardingStation.id
            p[K_DEST] = route.destinationStation.id
        }
    }

    suspend fun setAlertMode(mode: String) {
        context.dataStore.edit { it[K_ALERT_MODE] = mode }
    }

    suspend fun setRecordCsv(enabled: Boolean) {
        context.dataStore.edit { it[K_RECORD_CSV] = enabled }
    }

    suspend fun setKeepAliveGuideDone(done: Boolean) {
        context.dataStore.edit { it[K_KEEPALIVE_DONE] = done }
    }

    suspend fun setFirstRunDone(done: Boolean) {
        context.dataStore.edit { it[K_FIRST_RUN_DONE] = done }
    }

    suspend fun setDebugExpanded(expanded: Boolean) {
        context.dataStore.edit { it[K_DEBUG_EXPANDED] = expanded }
    }

    companion object {
        const val MODE_SOUND_VIB = "sound_vib"
        const val MODE_VIB_ONLY = "vib_only"

        private val K_LINE = stringPreferencesKey("last_route_line_id")
        private val K_DIR = stringPreferencesKey("last_route_direction_id")
        private val K_BOARD = stringPreferencesKey("last_route_boarding_station_id")
        private val K_DEST = stringPreferencesKey("last_route_destination_station_id")
        private val K_ALERT_MODE = stringPreferencesKey("alert_mode")
        private val K_RECORD_CSV = booleanPreferencesKey("record_csv")

        /** 一次性迁移标记：让「老用户判定」只发生一次（见 [migrateRecordCsvIfNeeded]） */
        private val K_RECORD_CSV_MIGRATED = booleanPreferencesKey("record_csv_migrated")
        private val K_KEEPALIVE_DONE = booleanPreferencesKey("keepalive_guide_done")
        private val K_FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        private val K_DEBUG_EXPANDED = booleanPreferencesKey("debug_expanded")

        /** 兼容总纲 7.3 的键名 `tuning_overrides_json` 将在 M3 调参面板启用 */
        const val K_TUNING_OVERRIDES = "tuning_overrides_json"
    }
}
