package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.model.MonitorUiState

/** 状态大卡片：当前站 / 下一站 / 还剩 N 站 / 进度条 / 已运行时间（总纲第八节） */
@Composable
fun StatusCard(state: MonitorUiState, modifier: Modifier = Modifier) {
    val arrived = state.arrived
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.error != null -> MaterialTheme.colorScheme.errorContainer
                arrived -> MaterialTheme.colorScheme.tertiaryContainer
                state.running -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.error != null) {
                Text("⚠ ${state.error}", style = MaterialTheme.typography.titleMedium)
                return@Column
            }

            Text(
                text = if (state.running) {
                    "${state.lineName ?: "-"} · ${state.directionName ?: "-"}"
                } else {
                    "未在监测"
                },
                style = MaterialTheme.typography.labelLarge,
            )

            if (state.running) {
                Text(
                    text = if (arrived) "已到达 ${state.destinationStation ?: "-"}" else (state.currentStation ?: "-"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "下一站：${state.nextStation ?: "-"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "还剩 ${state.remaining} 站（共 ${state.totalStops} 站，已数 ${state.stationCount}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val total = state.totalStops.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (state.stationCount.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("状态 ${state.state.name}", style = MaterialTheme.typography.bodySmall)
                    Text("已运行 ${formatElapsed(state.elapsedSec)}", style = MaterialTheme.typography.bodySmall)
                }
                if (state.totalStops == 1) {
                    Text(
                        "下一站就是目的站",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    "选择路线后点「开始监测」",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.lastEvent?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

fun formatElapsed(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
