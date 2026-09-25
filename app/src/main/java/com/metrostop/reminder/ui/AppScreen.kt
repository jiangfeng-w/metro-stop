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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: AppViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val error by vm.uiError.collectAsState()
    val scope = rememberCoroutineScope()
    var showCsvPicker by remember { mutableStateOf(false) }
    var csvFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("到站了") },
                actions = {
                    if (selectedTab == 2) {
                        TextButton(onClick = {
                            scope.launch {
                                csvFiles = vm.listSensorCsv()
                                showCsvPicker = true
                            }
                        }) { Text("回放") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("◉") },
                    label = { Text("监测") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("⚙") },
                    label = { Text("设置") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⋯") },
                    label = { Text("调试") },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> MonitorTab(vm, Modifier.padding(padding))
            1 -> SettingsTab(vm, Modifier.padding(padding))
            else -> DebugTab(
                vm = vm,
                csvFiles = csvFiles,
                onCsvFilesLoaded = { csvFiles = it },
                onShowCsvPicker = { showCsvPicker = it },
                modifier = Modifier.padding(padding),
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

@Composable
private fun MonitorTab(vm: AppViewModel, modifier: Modifier = Modifier) {
    val repo by vm.repo.collectAsState()
    val selection by vm.selection.collectAsState()
    val state by vm.monitorState.collectAsState()

    Column(
        modifier
            .fillMaxSize()
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
        Text(
            "注意：本 App 不用定位，靠加速度识别到站；跳站快车等情况下请以站名为准，可用 ±1 纠错。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SettingsTab(vm: AppViewModel, modifier: Modifier = Modifier) {
    val vibOnly by vm.alertVibOnly.collectAsState()
    val recordCsv by vm.recordCsv.collectAsState()
    val logsUsage by vm.logsUsage.collectAsState()
    val keepAliveDone by vm.keepAliveDone.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsCard(
            vibOnly = vibOnly,
            recordCsv = recordCsv,
            logsUsage = logsUsage,
            onVibOnly = vm::setAlertVibOnly,
            onRecordCsv = vm::setRecordCsv,
        )
        KeepAliveGuide(done = keepAliveDone, onDone = { vm.setKeepAliveDone(true) })
    }
}

@Composable
private fun DebugTab(
    vm: AppViewModel,
    csvFiles: List<File>,
    onCsvFilesLoaded: (List<File>) -> Unit,
    onShowCsvPicker: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.monitorState.collectAsState()
    val debugExpanded by vm.debugExpanded.collectAsState()
    val replayReport by vm.replayReport.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(debugExpanded) {
        if (debugExpanded) vm.loadReplayReport()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                    onCsvFilesLoaded(vm.listSensorCsv())
                    onShowCsvPicker(true)
                }
            },
            onLoadReport = vm::loadReplayReport,
            onCleanLogs = vm::cleanLogsNow,
        )
    }
}
