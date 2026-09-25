package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.platform.service.MonitorService
import kotlinx.coroutines.launch
import java.io.File

/**
 * 单屏（总纲第八节）：顶部栏 → 路线选择 → 状态大卡片 → 控制按钮 → 折叠调试面板 → 折叠保活向导。
 * 状态全部来自 SessionHolder（服务写、UI 只读）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: AppViewModel) {
    val repo by vm.repo.collectAsState()
    val selection by vm.selection.collectAsState()
    val state by vm.monitorState.collectAsState()
    val error by vm.uiError.collectAsState()
    val vibOnly by vm.alertVibOnly.collectAsState()
    val recordCsv by vm.recordCsv.collectAsState()
    val debugExpanded by vm.debugExpanded.collectAsState()
    val keepAliveDone by vm.keepAliveDone.collectAsState()
    val replayReport by vm.replayReport.collectAsState()

    val scope = rememberCoroutineScope()
    var showCsvPicker by remember { mutableStateOf(false) }
    var csvFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    // 调试面板打开时拉取一次最近回放报告
    LaunchedEffect(debugExpanded) {
        if (debugExpanded) vm.loadReplayReport()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("到站了") },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            csvFiles = vm.listSensorCsv()
                            showCsvPicker = true
                        }
                    }) { Text("回放") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            RouteSelector(
                repo = repo,
                selection = selection,
                enabled = !state.running,
                onSelectLine = vm::selectLine,
                onSelectDirection = vm::selectDirection,
                onSelectBoarding = vm::selectBoarding,
                onSelectDestination = vm::selectDestination,
            )

            StatusCard(state)

            Controls(
                state = state,
                canStart = vm.currentRoute() != null,
                onStart = vm::start,
                onStop = vm::stop,
                onTest = vm::testAlert,
                onCorrectUp = vm::correctUp,
                onCorrectDown = vm::correctDown,
            )

            SettingsCard(
                vibOnly = vibOnly,
                recordCsv = recordCsv,
                onVibOnly = vm::setAlertVibOnly,
                onRecordCsv = vm::setRecordCsv,
            )

            DebugPanel(
                state = state,
                debugExpanded = debugExpanded,
                measuredHz = state.measuredHz,
                usingLinear = state.usingLinearSensor,
                hasCsv = csvFiles.isNotEmpty() || state.recording,
                replayReport = replayReport,
                onToggle = { vm.setDebugExpanded(!debugExpanded) },
                onReplayLatest = {
                    scope.launch {
                        val latest = vm.listSensorCsv().firstOrNull()
                        if (latest == null) {
                            vm.reportError("logs 目录暂无 sensor CSV")
                        } else {
                            vm.replay(latest)
                        }
                    }
                },
                onPickCsv = {
                    scope.launch {
                        csvFiles = vm.listSensorCsv()
                        showCsvPicker = true
                    }
                },
                onLoadReport = vm::loadReplayReport,
            )

            KeepAliveGuide(done = keepAliveDone, onDone = { vm.setKeepAliveDone(true) })

            Text(
                "注意：本 App 不用定位，靠加速度识别到站；跳站快车等情况下请以站名为准，可用 ±1 纠错。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    if (showCsvPicker) {
        AlertDialog(
            onDismissRequest = { showCsvPicker = false },
            title = { Text("选择要回放的 sensor CSV") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (csvFiles.isEmpty()) {
                        Text("logs 目录暂无 sensor CSV")
                    }
                    csvFiles.take(30).forEach { f ->
                        TextButton(
                            onClick = {
                                showCsvPicker = false
                                vm.replay(f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(f.name) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showCsvPicker = false }) { Text("关闭") }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("知道了") } },
        )
    }
}
