package com.coc.suncode.core.ui.floatingwindows

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.coc.suncode.core.ui.theme.AppColors
import com.coc.suncode.core.util.basic.LogEntry

internal const val LOG_OVERLAY_WINDOW_TITLE = "SunCodeLogOverlay"

/** Keep the log entry the same size as the existing floating control icons. */
@Composable
internal fun FloatingLogButton(iconSize: Dp, selected: Boolean, onClick: () -> Unit) {
    FloatingLogControlButton(iconSize, selected, Icons.AutoMirrored.Filled.Article, "日志", onClick)
}

@Composable
internal fun FloatingLogHistoryButton(iconSize: Dp, selected: Boolean, onClick: () -> Unit) {
    FloatingLogControlButton(iconSize, selected, Icons.Default.History, "浏览日志", onClick)
}

@Composable
private fun FloatingLogControlButton(
    iconSize: Dp,
    selected: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    LogTooltip(description) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (selected) AppColors.Azure else AppColors.Azure.copy(alpha = 0.88f))
                .semantics {
                    contentDescription = description
                    this.selected = selected
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize().padding(5.dp)
            )
        }
    }
}

/** This host-owned UI must stay independent of the dynamically loaded JAR. */
@Composable
internal fun FloatingLogOverlay(
    logLines: List<LogEntry>,
    screenWidth: Int,
    screenHeight: Int,
    interactive: Boolean,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val anchor = LocalView.current
    val density = LocalDensity.current
    val parentComposition = rememberCompositionContext()
    val windowManager = remember(context) {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    val availableWidth = with(density) { screenWidth.toDp() }.coerceAtLeast(1.dp)
    val availableHeight = with(density) { screenHeight.toDp() }.coerceAtLeast(1.dp)
    val panelWidth = (availableWidth - 24.dp).coerceAtLeast(1.dp).coerceAtMost(420.dp)
    val panelHeight = (availableHeight - 24.dp).coerceAtLeast(1.dp).coerceAtMost(320.dp)
    val latestLines = rememberUpdatedState(logLines)
    val latestInteractive = rememberUpdatedState(interactive)
    val latestOnClear = rememberUpdatedState(onClear)
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    val params = remember(context) {
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            title = LOG_OVERLAY_WINDOW_TITLE
            // Android 12 checks window alpha, rather than the alpha of Compose pixels.
            val maximumOpacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            } else {
                1f
            }
            alpha = minOf(0.75f, maximumOpacity).coerceIn(0f, 1f)
            windowAnimations = 0
        }
    }
    val logView = remember(context, anchor, parentComposition) {
        ComposeView(context).apply {
            setParentCompositionContext(parentComposition)
            setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
            setContent {
                FloatingLogPanel(
                    logLines = latestLines.value,
                    interactive = latestInteractive.value,
                    onClear = { latestOnClear.value() },
                    onDismiss = { latestOnDismiss.value() }
                )
            }
        }
    }
    val widthPx = with(density) { panelWidth.roundToPx() }
    val heightPx = with(density) { panelHeight.roundToPx() }
    val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    fun updateInputFlags(inputActive: Boolean) {
        // Automated gestures take ownership even while manual history browsing is enabled.
        params.flags = baseFlags or if (latestInteractive.value && !inputActive) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    DisposableEffect(logView, windowManager) {
        params.width = widthPx
        params.height = heightPx
        params.x = ((screenWidth - widthPx) / 2).coerceAtLeast(0)
        params.y = ((screenHeight - heightPx) / 2).coerceAtLeast(0)
        updateInputFlags(LogOverlayCaptureGuard.isInputActive())
        windowManager.addView(logView, params)
        LogOverlayCaptureGuard.register(
            token = logView,
            setVisible = { visible ->
                logView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            },
            setInputActive = { active ->
                updateInputFlags(active)
                if (logView.isAttachedToWindow) windowManager.updateViewLayout(logView, params)
            }
        )
        onDispose {
            LogOverlayCaptureGuard.unregister(logView)
            windowManager.removeViewImmediate(logView)
            logView.disposeComposition()
        }
    }
    SideEffect {
        params.width = widthPx
        params.height = heightPx
        params.x = ((screenWidth - widthPx) / 2).coerceAtLeast(0)
        params.y = ((screenHeight - heightPx) / 2).coerceAtLeast(0)
        updateInputFlags(LogOverlayCaptureGuard.isInputActive())
        if (logView.isAttachedToWindow) windowManager.updateViewLayout(logView, params)
    }
}

@Composable
private fun FloatingLogPanel(
    logLines: List<LogEntry>,
    interactive: Boolean,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(interactive) {
        // Passive viewing resumes the live tail after browsing older entries.
        if (!interactive) followLatest = true
    }
    // Stop following while browsing history so incoming messages do not steal the scroll position.
    LaunchedEffect(isDragged) {
        if (isDragged) followLatest = false
    }
    LaunchedEffect(logLines.lastOrNull()?.id, followLatest) {
        if (followLatest && logLines.isNotEmpty()) {
            // A tail item also reveals the end of log entries taller than the viewport.
            listState.scrollToItem(logLines.size)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        shadowElevation = 4.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "运行日志",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (interactive) {
                    Text(
                        text = "浏览",
                        color = AppColors.Azure,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                LogToolbarButton(
                    imageVector = Icons.Default.VerticalAlignBottom,
                    description = "跟随最新日志",
                    selected = followLatest,
                    enabled = interactive,
                    onClick = { followLatest = !followLatest }
                )
                LogToolbarButton(
                    imageVector = Icons.Default.DeleteOutline,
                    description = "清空日志",
                    enabled = interactive,
                    onClick = {
                        onClear()
                        followLatest = true
                    }
                )
                LogToolbarButton(
                    imageVector = Icons.Default.Close,
                    description = "关闭日志",
                    enabled = interactive,
                    onClick = onDismiss
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            if (logLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无日志",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(logLines, key = { _, entry -> entry.id }) { index, entry ->
                        Text(
                            text = entry.line,
                            modifier = Modifier.padding(
                                top = if (index == 0) 8.dp else 0.dp,
                                bottom = if (index == logLines.lastIndex) 8.dp else 0.dp
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    item(key = "log-tail") { Spacer(modifier = Modifier.height(1.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LogToolbarButton(
    imageVector: ImageVector,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    LogTooltip(description) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp).semantics { this.selected = selected }
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = description,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> AppColors.Azure
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        focusable = false,
        content = content
    )
}
