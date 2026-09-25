package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置卡片：提醒方式（响铃+震动 / 仅震动）、是否记录 CSV */
@Composable
fun SettingsCard(
    vibOnly: Boolean,
    recordCsv: Boolean,
    onVibOnly: (Boolean) -> Unit,
    onRecordCsv: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("仅震动", style = MaterialTheme.typography.titleSmall)
                    Text("关闭响铃，只用震动提醒", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vibOnly, onCheckedChange = onVibOnly)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记录 CSV", style = MaterialTheme.typography.titleSmall)
                    Text("记录传感器与事件，供回放调参（约 15 MB/小时）", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = recordCsv, onCheckedChange = onRecordCsv)
            }
        }
    }
}
