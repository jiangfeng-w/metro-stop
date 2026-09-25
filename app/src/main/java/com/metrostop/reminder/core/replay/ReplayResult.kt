package com.metrostop.reminder.core.replay

/**
 * 离线回放结果：与现场 log 事件序列比对（M1 验收项「CSV 回放事件与现场完全一致」）。
 */
data class ReplayResult(
    val events: List<com.metrostop.reminder.core.model.DetectorEvent>,
    val finalStationCount: Int,
    val samples: Int,
    val sensorCsvName: String?,
) {
    /** 只保留「业务可见」的事件类型，便于与现场 log 对比 */
    fun comparableLines(): List<String> = events
        .filter { it.type in COMPARABLE }
        .map { e ->
            buildString {
                append(e.tMs)
                append(',')
                append(e.type.name)
                if (e.stationIndex != null) {
                    append(',')
                    append(e.stationIndex)
                }
                if (!e.note.isNullOrBlank()) {
                    append(',')
                    append(e.note)
                }
            }
        }

    companion object {
        val COMPARABLE = setOf(
            com.metrostop.reminder.core.model.DetectorEventType.WARMUP_DONE,
            com.metrostop.reminder.core.model.DetectorEventType.BRAKE_START,
            com.metrostop.reminder.core.model.DetectorEventType.BRAKE_ABORT,
            com.metrostop.reminder.core.model.DetectorEventType.STATION_ARRIVED,
            com.metrostop.reminder.core.model.DetectorEventType.FIRST_STOP_IGNORED,
            com.metrostop.reminder.core.model.DetectorEventType.STOP_SUSPECT,
            com.metrostop.reminder.core.model.DetectorEventType.DEPART,
            com.metrostop.reminder.core.model.DetectorEventType.ALERT_PREV,
            com.metrostop.reminder.core.model.DetectorEventType.ALERT_ARRIVED,
            com.metrostop.reminder.core.model.DetectorEventType.OVERSHOOT,
            com.metrostop.reminder.core.model.DetectorEventType.DATA_GAP,
            com.metrostop.reminder.core.model.DetectorEventType.DATA_RESUME,
            com.metrostop.reminder.core.model.DetectorEventType.MONITOR_END,
        )
    }
}
