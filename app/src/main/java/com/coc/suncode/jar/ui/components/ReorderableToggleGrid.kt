package com.coc.suncode.jar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateOf as stateOf
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.SettingDef
import kotlin.math.abs

private val ChipSelectedColor = Color(0xFF0084BF)
private val ChipDraggingColor = Color(0xFF005F8A)

/**
 * The item toggles of a profile, laid out in priority order (left to right, top to bottom = highest first).
 * Tap toggles an item; dragging sideways moves it (then anywhere, including other rows), and the new order is written to the matching priority
 * settings when the item is dropped. [priorityOf] maps an item to its rank setting, or null when it has none.
 */
@Composable
fun ReorderableToggleGrid(index: Int, items: List<SettingDef>, priorityOf: (SettingDef) -> SettingDef?) {
    fun rankOf(item: SettingDef): Int =
        priorityOf(item)?.let { GlobalVars.configStates["${it.key}_c$index"]?.value?.toIntOrNull() } ?: Int.MAX_VALUE

    // Re-read the saved order whenever the account changes or another editor (sync, reload) rewrote the ranks.
    val savedOrder = items.sortedBy(::rankOf)
    var order by remember(index, items) { mutableStateOf(savedOrder) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    if (draggingKey == null && order.map { it.key } != savedOrder.map { it.key }) order = savedOrder

    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var lastSwap by remember { mutableStateOf<String?>(null) }
    // Layout bounds in root coordinates, measured on the untranslated wrapper so dragging does not feed back.
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val sectionEnabled = LocalSettingsEnabledReader()

    fun persistOrder() {
        GlobalVars.isAutoRunEnabled = false
        order.forEachIndexed { i, item ->
            val def = priorityOf(item) ?: return@forEachIndexed
            GlobalVars.configStates.getOrPut("${def.key}_c$index") { stateOf((i + 1).toString()) }.value = (i + 1).toString()
        }
    }

    Text(
        text = "点击选择项目，左右拖动调整优先度（越靠前优先度越高）",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Gray,
        modifier = Modifier.padding(top = 4.dp)
    )
    FlowRow {
        order.forEach { item ->
            androidx.compose.runtime.key(item.key) {
                val state = GlobalVars.configStates.getOrPut("${item.key}_c$index") { stateOf(Schema.getDefaultValue("${item.key}_c$index")) }
                val selected = state.value == "1"
                val dragging = draggingKey == item.key
                val rank = order.indexOf(item) + 1
                // Read the state when tapped: the gesture coroutine outlives recompositions, so a captured
                // `selected` would go stale and every later tap would write the same value.
                val toggle = {
                    GlobalVars.isAutoRunEnabled = false
                    state.value = if (state.value == "1") "0" else "1"
                }
                Box(
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .onGloballyPositioned { bounds[item.key] = it.boundsInRoot() }
                        .pointerInput(item.key, sectionEnabled) {
                            if (!sectionEnabled) return@pointerInput
                            dragOrTapGestures(
                                touchSlop = viewConfiguration.touchSlop,
                                onTap = toggle,
                                onDragStart = { local ->
                                    GlobalVars.isAutoRunEnabled = false
                                    val origin = bounds[item.key]?.topLeft ?: Offset.Zero
                                    grabOffset = local
                                    pointer = origin + local
                                    lastSwap = null
                                    draggingKey = item.key
                                },
                                onDrag = { delta ->
                                    pointer += delta
                                    val hovered = bounds.entries.firstOrNull { (key, rect) -> key != item.key && rect.contains(pointer) }?.key
                                    // Chips differ in width, so a swap can leave the pointer over the same neighbour;
                                    // wait until it leaves that chip before swapping with it again.
                                    if (hovered != lastSwap) lastSwap = null
                                    val target = hovered?.takeIf { it != lastSwap }
                                    if (target != null) {
                                        lastSwap = target
                                        val from = order.indexOfFirst { it.key == item.key }
                                        val to = order.indexOfFirst { it.key == target }
                                        if (from != -1 && to != -1) order = order.toMutableList().apply { add(to, removeAt(from)) }
                                    }
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    persistOrder()
                                }
                            )
                        }
                        .semantics {
                            contentDescription = "${item.displayName}，优先度第${rank}"
                            stateDescription = if (selected) "已选择" else "未选择"
                            if (sectionEnabled) onClick { toggle(); true }
                        }
                ) {
                    ToggleChip(
                        label = item.displayName, selected = selected, dragging = dragging, enabled = sectionEnabled,
                        modifier = Modifier.graphicsLayer {
                            if (dragging) {
                                val origin = bounds[item.key]?.topLeft ?: Offset.Zero
                                translationX = pointer.x - grabOffset.x - origin.x
                                translationY = pointer.y - grabOffset.y - origin.y
                                scaleX = 1.08f
                                scaleY = 1.08f
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, dragging: Boolean, enabled: Boolean, modifier: Modifier) {
    val container = when {
        dragging -> ChipDraggingColor
        !selected -> Color.White
        enabled -> ChipSelectedColor
        else -> Color(0xFFD3D6DB)
    }
    val content = when {
        dragging || (selected && enabled) -> Color.White
        selected -> Color.DarkGray
        else -> Color.Black
    }
    Surface(
        modifier = modifier.padding(2.dp).height(32.dp),
        shape = RoundedCornerShape(8.dp),
        color = container,
        border = if (selected || dragging) null else BorderStroke(1.dp, Color.Gray),
        shadowElevation = if (dragging) 6.dp else 0.dp
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = content, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Tap = release without moving; a mostly-horizontal move past the touch slop starts dragging at once.
 * A mostly-vertical move is left unconsumed so the surrounding list can still scroll.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dragOrTapGestures(
    touchSlop: Float,
    onTap: () -> Unit, onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Wait for a release (tap) or for the finger to pass the touch slop (drag or scroll).
        var dragStart: PointerInputChange? = null
        while (true) {
            val change: PointerInputChange = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) {
                if (!change.isConsumed) onTap()
                return@awaitEachGesture
            }
            // The list consumed the move: it is scrolling.
            if (change.isConsumed) return@awaitEachGesture
            val moved = change.position - down.position
            if (moved.getDistance() > touchSlop) {
                // Vertical gestures belong to the list's scrolling.
                if (abs(moved.y) > abs(moved.x)) return@awaitEachGesture
                dragStart = change
                break
            }
        }
        val start = dragStart ?: return@awaitEachGesture
        start.consume()

        onDragStart(down.position)
        onDrag(start.position - down.position)
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                val delta = change.positionChange()
                if (delta != Offset.Zero) onDrag(delta)
                change.consume()
            }
        } finally {
            onDragEnd()
        }
    }
}
