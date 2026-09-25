package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.platform.keepalive.KeepAliveHelper

/** 折叠「保活五步向导」（总纲第六节）：跳转 best-effort，失败回退应用详情页 */
@Composable
fun KeepAliveGuide(
    done: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val steps = remember { KeepAliveHelper.steps(context) }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "▾ 保活五步向导" else "▸ 保活五步向导${if (done) "（已完成）" else ""}")
            }
            if (!expanded) return@Column

            Text(
                "建议按顺序完成以下 5 项设置，否则灭屏后容易被系统冻结导致漏计。",
                style = MaterialTheme.typography.bodySmall,
            )
            steps.forEach { step ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall)
                    Text(step.desc, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { KeepAliveHelper.open(context, step) }) { Text("去设置") }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    onDone()
                    expanded = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("已全部了解") }
        }
    }
}
