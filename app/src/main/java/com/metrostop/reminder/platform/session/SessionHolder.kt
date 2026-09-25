package com.metrostop.reminder.platform.session

import com.metrostop.reminder.core.model.MonitorUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 状态来源单一（硬性规则 7）：**只有服务写**，UI / 磁贴 / 小组件只读。
 * 磁贴 / 小组件 / 接收器内不得在主线程读 DataStore，只发 ACTION 给服务。
 */
object SessionHolder {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    /** 仅 MonitorService 调用 */
    fun update(value: MonitorUiState) {
        _state.value = value
    }

    fun clear() {
        _state.value = MonitorUiState()
    }
}
