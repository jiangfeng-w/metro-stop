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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.R
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
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_stop))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCorrectUp, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.btn_correct_up))
                    }
                    OutlinedButton(onClick = onCorrectDown, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.btn_correct_down))
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_start)) }
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_test_alert))
                }
            }
            if (!state.running && !canStart) {
                Text(stringResource(R.string.hint_pick_route), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
