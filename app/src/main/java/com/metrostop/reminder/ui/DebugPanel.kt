package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.model.MonitorUiState

/**
 * 折叠「实时调试面板」（M1 验收必需）：state / vib / H / n 实时值 + 采样率 + 传感器来源 + 离线回放。
 */
@Composable
fun DebugPanel(
    state: MonitorUiState,
    debugExpanded: Boolean,
    measuredHz: Double,
    usingLinear: Boolean,
    hasCsv: Boolean,
    replayReport: String?,
    onToggle: () -> Unit,
    onReplayLatest: () -> Unit,
    onPickCsv: () -> Unit,
    onLoadReport: () -> Unit,
    onCleanLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onToggle) {
                Text(if (debugExpanded) "▾ 实时调试面板" else "▸ 实时调试面板")
            }
            if (!debugExpanded) return@Column

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("状态", state.state.cnName, Modifier.weight(1.4f))
                Metric("车振(vib)", "%.3f".format(state.vib), Modifier.weight(1f))
                Metric("加减速(H)", "%.3f".format(state.h), Modifier.weight(1f))
                Metric("已计站(n)", "${state.stationCount}/${state.totalStops}", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("采样率", "%.1f Hz".format(measuredHz), Modifier.weight(1f))
                Metric(
                    "传感器",
                    if (usingLinear) "线性加速度" else "加速度计(降级)",
                    Modifier.weight(1.2f),
                )
                Metric("记录(CSV)", if (state.recording) "录制中" else "关", Modifier.weight(1f))
            }
            if (state.dataGap) {
                Text("⚠ 采样断流中（已重新预热）", color = MaterialTheme.colorScheme.error)
            }

            state.lastEvent?.let {
                Text("最近事件：$it", style = MaterialTheme.typography.bodySmall)
            }
            state.csvDir?.let {
                Text("日志目录：$it", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReplayLatest,
                    enabled = hasCsv,
                    modifier = Modifier.weight(1f),
                ) { Text("回放最近一次") }
                OutlinedButton(onClick = onPickCsv, modifier = Modifier.weight(1f)) { Text("选择 CSV…") }
                OutlinedButton(onClick = onLoadReport, modifier = Modifier.weight(1f)) { Text("查看报告") }
            }
            OutlinedButton(onClick = onCleanLogs, modifier = Modifier.fillMaxWidth()) {
                Text("立即清理日志（保留最近一次报告）")
            }
            replayReport?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "阈值见 TuningConfig.kt；调参后请导出 CSV 回放并留下回归用例。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
