package com.coc.suncode.core.ui.floatingwindows

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // Use an edge-to-edge half-screen layer instead of a small floating card.
    val panelWidth = availableWidth
    val panelHeight = availableHeight * 0.55f
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
            alpha = minOf(0.78f, maximumOpacity).coerceIn(0f, 1f)
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
        params.y = (screenHeight - heightPx).coerceAtLeast(0)
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
        params.y = (screenHeight - heightPx).coerceAtLeast(0)
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "运行日志",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 11.sp
                )
                if (interactive) {
                    Text(
                        text = "浏览",
                        color = Color.White,
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
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            if (logLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无日志",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(logLines, key = { _, entry -> entry.id }) { index, entry ->
                        Text(
                            text = entry.line,
                            modifier = Modifier.padding(
                                top = if (index == 0) 3.dp else 0.dp,
                                bottom = if (index == logLines.lastIndex) 3.dp else 0.dp
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp, lineHeight = 12.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
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
            modifier = Modifier.size(32.dp).semantics { this.selected = selected }
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = description,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.4f)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(16.dp)
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
