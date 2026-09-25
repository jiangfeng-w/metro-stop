package com.metrostop.reminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.route.LineRepository

/**
 * 路线选择：线路 / 方向 / 上车站 / 目的站；**目的站限选上车站之后**的站（总纲第八节）。
 * 上车站 / 目的站 / 方向任一变化都清空下游，避免出现非法组合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSelector(
    repo: LineRepository,
    selection: RouteSelection,
    enabled: Boolean,
    onSelectLine: (String) -> Unit,
    onSelectDirection: (String) -> Unit,
    onSelectBoarding: (String) -> Unit,
    onSelectDestination: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = repo.lines
    val line = selection.lineId?.let { repo.line(it) }
    val directions = line?.directions ?: emptyList()
    val direction = if (line != null && selection.directionId != null) {
        line.directions.firstOrNull { it.id == selection.directionId }
    } else {
        null
    }
    val stations: List<Station> = direction?.stations ?: emptyList()
    val boardingIdx = stations.indexOfFirst { it.id == selection.boardingId }
    val destinations = if (boardingIdx >= 0) stations.drop(boardingIdx + 1) else emptyList()

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("路线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            DropdownField(
                label = "线路",
                value = line?.name,
                options = lines.map { it.id to it.name },
                enabled = enabled && lines.isNotEmpty(),
                onPick = onSelectLine,
            )

            DropdownField(
                label = "方向",
                value = direction?.name,
                options = directions.map { it.id to it.name },
                enabled = enabled && directions.isNotEmpty(),
                onPick = onSelectDirection,
            )

            DropdownField(
                label = "上车站",
                value = stations.firstOrNull { it.id == selection.boardingId }?.name,
                options = stations.map { it.id to it.name },
                enabled = enabled && stations.isNotEmpty(),
                onPick = onSelectBoarding,
            )

            DropdownField(
                label = "目的站",
                value = destinations.firstOrNull { it.id == selection.destinationId }?.name,
                options = destinations.map { it.id to it.name },
                enabled = enabled && destinations.isNotEmpty(),
                onPick = onSelectDestination,
            )

            if (boardingIdx >= 0 && destinations.isEmpty()) {
                Text("上车站之后没有可选站点", style = MaterialTheme.typography.bodySmall)
            } else if (selection.destinationId != null) {
                val k = stations.indexOfFirst { it.id == selection.destinationId } - boardingIdx
                if (k > 0) {
                    Text(
                        "本站起共 $k 站（到站自动提醒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = value ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text("请选择$label") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onPick(id)
                    },
                )
            }
        }
    }
}
