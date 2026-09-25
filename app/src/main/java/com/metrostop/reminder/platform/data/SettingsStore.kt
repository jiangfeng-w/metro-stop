package com.metrostop.reminder.platform.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrostop.reminder.core.model.RouteSpec
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
    val recordCsv: Flow<Boolean> = context.dataStore.data.map { it[K_RECORD_CSV] ?: true }
    val keepAliveGuideDone: Flow<Boolean> = context.dataStore.data.map { it[K_KEEPALIVE_DONE] ?: false }
    val firstRunDone: Flow<Boolean> = context.dataStore.data.map { it[K_FIRST_RUN_DONE] ?: false }
    val debugExpanded: Flow<Boolean> = context.dataStore.data.map { it[K_DEBUG_EXPANDED] ?: false }

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
        private val K_KEEPALIVE_DONE = booleanPreferencesKey("keepalive_guide_done")
        private val K_FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        private val K_DEBUG_EXPANDED = booleanPreferencesKey("debug_expanded")

        /** 兼容总纲 7.3 的键名 `tuning_overrides_json` 将在 M3 调参面板启用 */
        const val K_TUNING_OVERRIDES = "tuning_overrides_json"
    }
}
