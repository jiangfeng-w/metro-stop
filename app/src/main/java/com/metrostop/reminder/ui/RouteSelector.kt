package com.metrostop.reminder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metrostop.reminder.core.model.Station
import com.metrostop.reminder.core.route.LineRepository
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 路线选择：线路 / 方向 / 上车站 / 目的站；**目的站限选上车站之后**的站（总纲第八节）。
 * 上车站 / 目的站 / 方向任一变化都清空下游，避免出现非法组合。
 *
 * 下拉框实现（2026-09-27 `ui-jank-diagnosis`，依据真机实测）：
 * - **Material 原版外观**：字段就是 `OutlinedTextField`（浮动标签 / 圆角外框 / 尾箭头），
 *   上方盖一层透明点击层把点击接过来；
 * - **覆盖式浮层，不新建窗口**：实测掉帧主源是 `ExposedDropdownMenuBox` 每次展开都
 *   **新建 Popup 独立窗口 + 首绘 + 入场动画**（1 项 vs 7 项菜单首帧 104.9ms vs 115.4ms →
 *   与菜单项数量无关）。本实现把菜单渲染为**页面内顶层浮层**（覆盖在字段下方内容之上），
 *   位置由锚点字段的窗口坐标算出，零窗口开销；
 * - **单一展开源**（[RouteDropdownHost.openField]）：任一时刻最多一个菜单展开。
 *
 * 实测（详见诊断报告 §5.4/§5.5）：开合 / 互切 janky 27~38% → 0.4~0.5%。
 */
@Composable
fun RouteSelector(
    repo: LineRepository,
    selection: RouteSelection,
    enabled: Boolean,
    host: RouteDropdownHost,
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

    // 选项列表仅在数据源变化时重建（稳定引用，避免每次重组新建 list）
    val lineOptions = remember(lines) { lines.map { it.id to it.name } }
    val directionOptions = remember(directions) { directions.map { it.id to it.name } }
    val stationOptions = remember(stations) { stations.map { it.id to it.name } }
    val destinationOptions = remember(destinations) { destinations.map { it.id to it.name } }

    // 进入监测（enabled=false）时强制收起，避免残留展开态
    LaunchedEffect(enabled, host) { if (!enabled) host.close() }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("路线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            DropdownField(
                host = host,
                id = FIELD_LINE,
                label = "线路",
                value = line?.name,
                options = lineOptions,
                enabled = enabled && lineOptions.isNotEmpty(),
                onPick = onSelectLine,
            )

            DropdownField(
                host = host,
                id = FIELD_DIRECTION,
                label = "方向",
                value = direction?.name,
                options = directionOptions,
                enabled = enabled && directionOptions.isNotEmpty(),
                onPick = onSelectDirection,
            )

            DropdownField(
                host = host,
                id = FIELD_BOARDING,
                label = "上车站",
                value = stations.firstOrNull { it.id == selection.boardingId }?.name,
                options = stationOptions,
                enabled = enabled && stationOptions.isNotEmpty(),
                onPick = onSelectBoarding,
            )

            DropdownField(
                host = host,
                id = FIELD_DESTINATION,
                label = "目的站",
                value = destinations.firstOrNull { it.id == selection.destinationId }?.name,
                options = destinationOptions,
                enabled = enabled && destinationOptions.isNotEmpty(),
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

private const val FIELD_LINE = "line"
private const val FIELD_DIRECTION = "direction"
private const val FIELD_BOARDING = "boarding"
private const val FIELD_DESTINATION = "destination"

/**
 * 下拉浮层宿主：持有「当前展开的字段 + 锚点位置 + 选项 + 回调」，
 * 并登记四个字段的窗口矩形，供遮罩层做「点击另一字段 → 直接切换」的命中判定。
 * 由页面（MonitorTab）`remember` 后传给 [RouteSelector] 与 [RouteDropdownOverlay]。
 */
@Stable
class RouteDropdownHost {
    var openField by mutableStateOf<String?>(null)
        private set

    /** 锚点字段在窗口坐标系中的位置（打开时由字段捕获） */
    var anchorBounds by mutableStateOf<Rect?>(null)
        private set

    var options by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    var onPick by mutableStateOf<((String) -> Unit)?>(null)
        private set

    /** 字段登记表：id → (窗口矩形, 是否可用, 点击动作)。遮罩层命中即调用动作（切换/打开） */
    private class FieldEntry(val anchor: Rect, val enabled: Boolean, val onTap: () -> Unit)

    // 普通 HashMap（非快照状态）：只在布局阶段写、指针阶段读，均为主线程，避免布局期写状态
    private val fields = HashMap<String, FieldEntry>()

    internal fun register(id: String, anchor: Rect?, enabled: Boolean, onTap: () -> Unit) {
        if (anchor == null) {
            fields.remove(id)
        } else {
            fields[id] = FieldEntry(anchor, enabled, onTap)
        }
    }

    /** 遮罩层点击：命中已登记的可用字段 → 切换；返回是否命中 */
    internal fun tapFieldAt(windowPos: androidx.compose.ui.geometry.Offset): Boolean {
        val id = openField ?: return false
        // 优先命中「非当前展开字段」的其他字段
        val hit = fields.entries.firstOrNull { (key, e) ->
            key != id && e.enabled && e.anchor.contains(windowPos)
        } ?: return false
        hit.value.onTap()
        return true
    }

    fun open(id: String, anchor: Rect?, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        openField = id
        anchorBounds = anchor
        this.options = options
        this.onPick = onPick
    }

    /** 锚点位置变化（如页面滚动）时同步更新，菜单跟随字段 */
    fun updateAnchor(anchor: Rect?) {
        if (openField != null) anchorBounds = anchor
    }

    fun close() {
        openField = null
        anchorBounds = null
        options = emptyList()
        onPick = null
    }
}

/**
 * Material 外观的下拉字段：
 * - 折叠态 = 原版 [OutlinedTextField]（readOnly）+ 透明点击层（点击 → 打开浮层）；
 * - 展开态 = 由 [RouteDropdownOverlay] 在页面上层渲染菜单（**不创建 Popup 窗口**）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    host: RouteDropdownHost,
    id: String,
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val expanded = host.openField == id
    val openAction = {
        if (expanded) host.close()
        else host.open(id, anchor, options, onPick)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val b = it.boundsInWindow()
                anchor = b
                // 展开期间页面滚动 → 菜单跟随锚点
                if (host.openField == id) host.updateAnchor(b)
                // 登记给遮罩层：点击本字段可直接切换（避免"第一下被吞"）
                host.register(id, b, enabled, openAction)
            },
    ) {
        Box {
            OutlinedTextField(
                value = value ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(label) },
                placeholder = { Text("请选择$label") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                // 展开时用聚焦色高亮外框（Popup 版由焦点驱动，这里由展开态驱动）
                colors = if (expanded && enabled) {
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // 透明点击层：盖在字段上方接管点击（readOnly 字段本身不弹键盘）
            if (enabled) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable { openAction() },
                )
            }
        }
    }
}

/**
 * 覆盖式下拉浮层（由页面在内容之上渲染，**同窗口内**）：
 * - 默认贴在锚点字段下方，覆盖其下面的内容；
 * - 下方空间不足且上方更大时**向上翻转**（与 Material 弹出菜单一致）；
 * - 菜单太高时内部滚动（限高 = 可用空间）；
 * - 点菜单外任意处：关闭（且消费该次点击，不会误触到下面的字段——与 Popup 的
 *   ACTION_OUTSIDE 行为一致）；系统返回键：关闭。
 */
@Composable
fun RouteDropdownOverlay(host: RouteDropdownHost, modifier: Modifier = Modifier) {
    val id = host.openField ?: return
    val anchor = host.anchorBounds ?: return
    val options = host.options
    val onPick = host.onPick ?: return

    var container by remember { mutableStateOf<Rect?>(null) }

    BackHandler { host.close() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { container = it.boundsInWindow() },
    ) {
        val c = container ?: return@Box
        val density = LocalDensity.current
        val itemHeightPx = with(density) { 48.dp.toPx() }
        val paddingPx = with(density) { 16.dp.toPx() }
        val gapPx = with(density) { 4.dp.toPx() }

        val estimatedPx = options.size * itemHeightPx + paddingPx
        val spaceBelowPx = (c.bottom - anchor.bottom - gapPx).coerceAtLeast(0f)
        val spaceAbovePx = (anchor.top - c.top - gapPx).coerceAtLeast(0f)
        // 下方放不下且上方更大 → 向上翻转
        val openUp = estimatedPx > spaceBelowPx && spaceAbovePx > spaceBelowPx
        val maxHeightPx = (if (openUp) spaceAbovePx else spaceBelowPx)
            .coerceAtLeast(itemHeightPx) // 至少容纳一项，避免极端情况下高度为 0
        val menuHeightPx = min(estimatedPx, maxHeightPx)

        val left = anchor.left - c.left
        val top = if (openUp) {
            (anchor.top - c.top) - gapPx - menuHeightPx
        } else {
            (anchor.bottom - c.top) + gapPx
        }
        val width = anchor.width
        val menuRect = Rect(left, top, left + width, top + menuHeightPx)

        // 遮罩层：处理「菜单矩形之外」的按下。
        // - 命中另一个已启用字段 → 直接切换（关旧开新，一次点击完成，不吞点）；
        // - 其它位置 → 关闭菜单并消费本次点击（与 Popup 的 ACTION_OUTSIDE 一致）。
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(id, left, top, width, menuHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!menuRect.contains(down.position)) {
                            down.consume()
                            // 转成窗口坐标做字段命中（容器左上角 + 局部位置）
                            val windowPos = androidx.compose.ui.geometry.Offset(
                                (container?.left ?: 0f) + down.position.x,
                                (container?.top ?: 0f) + down.position.y,
                            )
                            if (!host.tapFieldAt(windowPos)) host.close()
                        }
                    }
                },
        )

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { width.toDp() })
                .heightIn(max = with(density) { maxHeightPx.toDp() }),
        ) {
            // LazyColumn：真实线路最长为 6 号线 56 站，一次性 forEach 组合会拖首帧；
            // 只组合可见项（菜单本身限高 + 内部滚动）。锚点定位 / 上翻 / 限高逻辑不变。
            LazyColumn(Modifier.padding(vertical = 8.dp)) {
                items(options.size, key = { options[it].first }) { i ->
                    val (optionId, name) = options[i]
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onPick(optionId)
                            host.close()
                        },
                    )
                }
            }
        }
    }
}
