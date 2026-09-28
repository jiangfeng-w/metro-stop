package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metrostop.reminder.core.model.MonitorUiState
import kotlinx.coroutines.flow.StateFlow

/** 状态大卡片：当前站 / 下一站 / 还剩 N 站 / 进度条 / 已运行时间（总纲第八节） */
@Composable
fun StatusCard(
    state: MonitorUiState,
    elapsedSecFlow: StateFlow<Double>,
    modifier: Modifier = Modifier,
) {
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
                    Text("状态 ${state.state.cnName}", style = MaterialTheme.typography.bodySmall)
                    // 每秒变化的时间放到独立叶子：1 Hz 重组只影响这一个 Text
                    ElapsedText(elapsedSecFlow)
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

/**
 * 已运行时间叶子（每秒走字）。
 *
 * 承载物是**原生 TextView**（`AndroidView`）：Compose 全窗口共享一个 RenderNode，内容失效会
 * 触发整窗 draw 命令重录（实测 4~9ms/次，debug 下必超 8.3ms@120Hz 预算 → 每秒一次 janky）；
 * TextView 有自己的 RenderNode，每秒改文字只重录它自己（<1ms 量级）。
 * 秒值经 `LaunchedEffect` 直接写 `textView.text`（**不进组合/状态**），每秒零组合零布局。
 * 槽位固定宽度（按最宽样板测量），避免文本变宽推挤布局。
 */
@Composable
private fun ElapsedText(elapsedSecFlow: StateFlow<Double>) {
    val style = MaterialTheme.typography.bodySmall
    val contentColor = LocalContentColor.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val slot = remember(textMeasurer, style) {
        textMeasurer.measure(text = "已运行 00:00:00", style = style)
    }
    val slotWidth = with(density) { slot.size.width.toDp() }
    val slotHeight = with(density) { slot.size.height.toDp() }
    val textSizeSp = style.fontSize.value
    val colorArgb = contentColor.toArgb()

    val textViewRef = remember { mutableStateOf<android.widget.TextView?>(null) }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                includeFontPadding = false
                isSingleLine = true
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                text = "已运行 0:00"
                textViewRef.value = this
            }
        },
        update = { tv ->
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            tv.setTextColor(colorArgb)
            textViewRef.value = tv
        },
        modifier = Modifier.width(slotWidth).height(slotHeight),
    )

    // 每秒写 textview.text：只失效它自己的 RenderNode，不触发任何 Compose 组合/布局
    LaunchedEffect(elapsedSecFlow) {
        elapsedSecFlow.collect { sec ->
            textViewRef.value?.text = "已运行 " + formatElapsed(sec)
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
