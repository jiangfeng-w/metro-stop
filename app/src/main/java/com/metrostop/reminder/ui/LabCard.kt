package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.lab.LabScenarios
import com.metrostop.reminder.platform.lab.LabHolder

/**
 * 「实验室数据采集」卡片（调试 Tab，lab-data-collection，短期调试需求）：
 * 权限请求 + 开始 / 停止 + **场景清单逐项标记**（core/lab/LabScenarios 同时是采集操作清单）。
 *
 * 标记语义 =「进入该场景」：进入新场景时点对应行，两次标记之间归上一个场景，无需起止配对。
 * 与监测无关，不读 SessionHolder；状态只来自 LabHolder。
 */
@Composable
fun LabCard(
    state: LabHolder.State,
    permissionsGranted: Int,
    permissionsTotal: Int,
    onGrantPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMarkScenario: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }
    // 通勤精简模式（UI 局部状态，不持久化）：只显示 停稳/乘车 标记，防通勤途中误点
    var commuteMode by rememberSaveable { mutableStateOf(false) }
    val currentName = state.currentScenario?.let { LabScenarios.byId[it]?.name ?: it }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("实验室数据采集（短期调试）", style = MaterialTheme.typography.titleMedium)

            if (!state.running) {
                Text(
                    "为步态标定 / 阈值重标 / 指纹假设裁决采集全量数据：IMU、气压、光照、步数、定位、GNSS、蜂窝信号、Wi-Fi 指纹（BSSID 哈希）、小区序列。数据仅存本机 logs/lab_* 目录。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onGrantPermissions, modifier = Modifier.weight(1f)) {
                        Text(if (permissionsGranted == permissionsTotal) "权限已齐（$permissionsGranted/$permissionsTotal）" else "授予权限（$permissionsGranted/$permissionsTotal）")
                    }
                    Button(
                        onClick = onStart,
                        enabled = permissionsGranted > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("开始采集") }
                }
                if (permissionsGranted < permissionsTotal) {
                    Text(
                        "⚠ 定位 / 身体活动 / 电话状态 / Wi-Fi 附近设备未全授，未授权的流不采集（其余照常）。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text("采集进行中：${state.stamp ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "流：${state.activeStreams.joinToString(" ")}\n已标 ${state.marks} 次" +
                        (currentName?.let { " · 当前：$it" } ?: " · 尚未标记场景") +
                        " · 已写 ${state.linesWritten} 行" +
                        if (state.droppedLines > 0) " · ⚠丢弃 ${state.droppedLines} 行" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "进入新场景时，在下面列表点对应行的「标记」。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.running) {
                // ---- 通勤精简开关：只显示 停稳/乘车 两类标记（2026-09-30 用户提出，防误点）----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "通勤精简（只显示 停稳 / 乘车）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = commuteMode, onCheckedChange = { commuteMode = it })
                }
                // ---- 场景清单（= 采集操作清单）：分组逐项标记 ----
                LabScenarios.Group.entries.forEach { group ->
                    val items = LabScenarios.visible(commuteMode).filter { it.group == group }
                    if (items.isEmpty()) return@forEach
                    Text(
                        group.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    items.forEach { sc ->
                        val count = state.marksByScenario[sc.id] ?: 0
                        val isCurrent = state.currentScenario == sc.id
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (if (isCurrent) "▶ " else "") + sc.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (count > 0) {
                                Text("×$count", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(onClick = { onMarkScenario(sc.id) }) { Text("标记") }
                        }
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止采集") }
            }

            TextButton(onClick = { showHelp = true }) {
                Text("怎么标？（标记说明）", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("标记说明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• **开始时标**：到达新场景的那一刻点一次「标记」即可，不用记起止。")
                    Text("• 两次标记之间的数据自动归给上一次标记的场景；不用标「结束」——下一场景的开始或「停止采集」会自动封口。")
                    Text("• 忘了及时标？想起来就补标，晚几十秒没关系。")
                    Text("• 特例：乘车中途列车停稳 → 标「列车进站停稳」；车再开 → 标回原来的乘车场景（可选，标了更好）。")
                    Text("• 每行右边的 ×N 是已标次数，方便核对有没有漏；场景前有 ▶ 表示当前场景。")
                    Text("• 来不及标：通知栏「📍标记」按钮做无场景兜底；光强/气压/步数/定位事后都能辅助对段。")
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}
