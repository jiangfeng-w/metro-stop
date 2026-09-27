package com.metrostop.reminder.platform.lab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lab 采集状态单一来源（镜像 `SessionHolder` / 硬性规则 7 同款）：**只有 LabCollectorService 写**，
 * UI（LabCard）只读。与 SessionHolder 完全独立，互不读写。
 */
object LabHolder {

    data class State(
        val running: Boolean = false,
        /** 本次采集的目录名（lab_<stamp>） */
        val stamp: String? = null,
        /** 已授权 / 已开启的流 */
        val activeStreams: List<String> = emptyList(),
        /** 📍 标记总次数 */
        val marks: Int = 0,
        /** 各场景标记次数（key = LabScenarios.Scenario.id 或 generic） */
        val marksByScenario: Map<String, Int> = emptyMap(),
        /** 当前场景（最近一次带场景名的标记；null = 尚未标记） */
        val currentScenario: String? = null,
        /** 写盘行数（约 30 s 刷新一次 UI 显示，不做高频上报） */
        val linesWritten: Long = 0L,
        /** 被队列丢弃的行数 */
        val droppedLines: Int = 0,
        val lastMarkAtMs: Long = 0L,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 仅 LabCollectorService 调用 */
    fun update(value: State) {
        _state.value = value
    }

    fun clear() {
        _state.value = State()
    }
}
