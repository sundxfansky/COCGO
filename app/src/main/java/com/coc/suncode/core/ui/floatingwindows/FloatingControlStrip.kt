package com.coc.suncode.core.ui.floatingwindows

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/** Reveal the toolbar and the same full-size icon together, without changing their edge anchor. */
@Composable
internal fun FloatingControlStrip(
    expansion: Float,
    isAtRightSide: Boolean,
    iconModifier: Modifier = Modifier,
    onContentWidthChange: (Int) -> Unit = {},
    icon: @Composable () -> Unit,
    toolbar: @Composable () -> Unit
) {
    Layout(
        content = {
            ClippedControlWidth(
                fraction = 0.5f + 0.5f * expansion,
                alignRight = !isAtRightSide,
                modifier = iconModifier,
                content = icon
            )
            ClippedControlWidth(
                fraction = expansion,
                alignRight = isAtRightSide,
                content = toolbar
            )
        },
        modifier = Modifier.clipToBounds()
    ) { measurables, constraints ->
        // Measure content independently of the current native window width during a resize.
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
        val mainIcon = measurables[0].measure(looseConstraints)
        val controls = measurables[1].measure(looseConstraints)
        val contentWidth = mainIcon.width + controls.width
        onContentWidthChange(contentWidth)
        val width = constraints.constrainWidth(contentWidth)
        val height = maxOf(mainIcon.height, controls.height)
        layout(width, height) {
            // Absolute placement keeps the physical docking edge independent of the UI language.
            // Anchor drawing to the width already granted by WindowManager, including intermediate frames.
            mainIcon.place(if (isAtRightSide) width - mainIcon.width else 0, (height - mainIcon.height) / 2)
            controls.place(if (isAtRightSide) width - mainIcon.width - controls.width else mainIcon.width, (height - controls.height) / 2)
        }
    }
}

@Composable
private fun ClippedControlWidth(
    fraction: Float,
    alignRight: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val child = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = ((child?.width ?: 0) * fraction.coerceIn(0f, 1f)).roundToInt()
        val height = child?.height ?: 0
        layout(width, height) {
            child?.place(if (alignRight) width - child.width else 0, 0)
        }
    }
}
