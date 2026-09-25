package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.model.MonitorUiState

/** 主按钮区：开始 ⇄ 结束；副：测试提醒 / ±1 纠错（总纲第八节） */
@Composable
fun Controls(
    state: MonitorUiState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    onCorrectUp: () -> Unit,
    onCorrectDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("结束监测") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCorrectUp, modifier = Modifier.weight(1f)) { Text("我已多过一站（＋1）") }
                    OutlinedButton(onClick = onCorrectDown, modifier = Modifier.weight(1f)) { Text("多算了一站（−1）") }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始监测") }
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("测试提醒") }
            }
            if (!state.running && !canStart) {
                Text("请先选好线路、方向、上车站与目的站", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
