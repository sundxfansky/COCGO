package com.coc.suncode.core.ui.floatingwindows

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.exit.AppExitHelper
import com.coc.suncode.core.util.exit.AppExitHelper.restoreDefaultInputMethod
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Base DPI for consistent physical size across devices
private const val BASE_DPI = 320f

// Base icon size at DPI 320 (38dp)
private val BASE_ICON_SIZE_DP = 38.dp

/**
 * Calculate icon size that maintains consistent physical size across different DPI devices.
 * At DPI 320, the size will be 38dp. On other DPI devices, it scales proportionally.
 */
@Composable
fun getConsistentIconSize(): Dp {
    val density = LocalDensity.current
    val currentDpi = density.density * 160f
    val scaleFactor = BASE_DPI / currentDpi
    return BASE_ICON_SIZE_DP * scaleFactor
}

@Suppress("AssignedValueIsNeverRead")
@Composable
fun ControlWindow(
    externalInteractionCount: Int = 0,
    isAtRightSide: Boolean = false,
    currentX: Int = 0,
    currentY: Int = 0,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
    onOpenMainUI: () -> Unit = {},
    isSwitchingAccount: Boolean = false,
    onSwitchAccount: (Int) -> Unit = {},
    onContentWidthChange: (Int) -> Unit = {},
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val expansion = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    val setExpanded: (Boolean) -> Unit = { expanded ->
        if (isExpanded != expanded) {
            isExpanded = expanded
            // Cancel and reverse immediately on a tap, before the next animation frame can advance.
            animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                expansion.animateTo(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            }
        }
    }
    var internalInteractionCount by remember { mutableIntStateOf(0) }
    var isPlaying by remember { GlobalVars.isPlaying }
    var showLogs by remember { mutableStateOf(false) }
    var logInteractionEnabled by remember { mutableStateOf(false) }
    val logLines by ShowMessage.logLines.collectAsState()
    var accountSwitchState by remember { mutableStateOf(AccountSwitchState()) }

    LaunchedEffect(isExpanded, GlobalVars.pluginUI, isSwitchingAccount) {
        accountSwitchState = AccountSwitchState()
        val switcher = GlobalVars.pluginUI as? RuntimeAccountSwitcher ?: return@LaunchedEffect
        if (!isExpanded || isSwitchingAccount) return@LaunchedEffect
        var reportedFailure = false
        // Query only while expanded, and keep all root I/O off the main thread.
        while (true) {
            try {
                accountSwitchState = withContext(Dispatchers.IO) { switcher.accountSwitchState() }
                reportedFailure = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                accountSwitchState = AccountSwitchState()
                if (!reportedFailure) ShowMessage.record("读取相邻账号存档失败：${error.message}", throwable = error)
                reportedFailure = true
            }
            delay(1000)
        }
    }

    val switchAccount: (Int) -> Unit = { target ->
        internalInteractionCount++
        isPlaying = false
        onSwitchAccount(target)
    }

    LaunchedEffect(isPlaying) {
        // Resuming automation always returns the log window to touch-through mode.
        if (isPlaying) logInteractionEnabled = false
    }
    // Viewing logs must not pause automation or duplicate its temporary messages.
    DisposableEffect(showLogs) {
        ShowMessage.setLogOverlayVisible(showLogs)
        onDispose { ShowMessage.setLogOverlayVisible(false) }
    }

    val pauseForInteraction = {
        if (isPlaying && !GlobalVars.isAdPlaying) {
            isPlaying = false
            ShowMessage("辅助已暂停，为节省资源，暂停5分钟后会自动退出。", false)
        }
    }

    LaunchedEffect(internalInteractionCount, externalInteractionCount, isDragging) {
        if (isDragging) return@LaunchedEffect
        delay(2500)
        setExpanded(false)
    }

    val iconSize = getConsistentIconSize()

    val mainIcon = remember {
        context.assets.open("main_icon.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
    val playIcon = remember {
        context.assets.open("play.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
    val pauseIcon = remember {
        context.assets.open("pause.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
    val settingIcon = remember {
        context.assets.open("setting.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
    val exitIcon = remember {
        context.assets.open("exit.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
    // Read current callbacks without restarting an in-progress pointer gesture on recomposition.
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { latestOnDragStart() },
            onDragEnd = { latestOnDragEnd() },
            onDragCancel = { latestOnDragEnd() },
            onDrag = { change, dragAmount ->
                change.consume()
                latestOnDrag(dragAmount.x, dragAmount.y)
            }
        )
    }

    FloatingControlStrip(
        expansion = expansion.value,
        isAtRightSide = isAtRightSide,
        onContentWidthChange = onContentWidthChange,
        iconModifier = dragModifier
            .semantics {
                contentDescription = "Main Icon"
                stateDescription = if (isExpanded) "工具栏已展开" else "工具栏已收起"
            }
            // A ripple at the screen edge looks like a flash while the viewport changes width.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                setExpanded(!isExpanded)
                internalInteractionCount++
            },
        icon = {
            // Keep one bitmap at one size; only its clipped viewport changes during the animation.
            Image(
                bitmap = mainIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize).padding(2.dp)
            )
        },
        toolbar = {
            // Keep controls composed until the closing animation finishes.
            if (isExpanded || expansion.value > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isAtRightSide) {
                        Image(
                            bitmap = exitIcon,
                            contentDescription = "Exit",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable {
                                    // Exit immediately without a confirmation dialog.
                                    AppExitHelper.exitApplication(context)
                                }
                        )
                        Image(
                            bitmap = settingIcon,
                            contentDescription = "Setting",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable(enabled = !isSwitchingAccount) {
                                    // Disable setting icon when ad is playing
                                    if (GlobalVars.isAdPlaying) return@clickable
                                    internalInteractionCount++
                                    pauseForInteraction()
                                    AppStateManager.setMode(AppMode.Main)
                                    restoreDefaultInputMethod()
                                    onOpenMainUI()
                                }
                        )
                        FloatingAccountButtons(
                            iconSize = iconSize,
                            state = accountSwitchState,
                            enabled = !isSwitchingAccount && !GlobalVars.isAdPlaying,
                            onSwitchAccount = switchAccount
                        )
                        FloatingLogButton(iconSize = iconSize, selected = showLogs) {
                            internalInteractionCount++
                            showLogs = !showLogs
                            logInteractionEnabled = false
                        }
                        if (showLogs) {
                            FloatingLogHistoryButton(iconSize = iconSize, selected = logInteractionEnabled) {
                                internalInteractionCount++
                                logInteractionEnabled = !logInteractionEnabled
                            }
                        }
                        Image(
                            bitmap = if (isPlaying) pauseIcon else playIcon,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable(enabled = !isSwitchingAccount) {
                                    // Disable play/pause icon when ad is playing
                                    if (GlobalVars.isAdPlaying) return@clickable
                                    internalInteractionCount++
                                    isPlaying = !isPlaying
                                    if (!isPlaying) {
                                        ShowMessage("暂停中，请稍后...")
                                        restoreDefaultInputMethod()
                                        ShowMessage("已暂停")
                                    }
                                }
                        )
                    } else {
                        Image(
                            bitmap = if (isPlaying) pauseIcon else playIcon,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable(enabled = !isSwitchingAccount) {
                                    // Disable play/pause icon when ad is playing
                                    if (GlobalVars.isAdPlaying) return@clickable
                                    internalInteractionCount++
                                    isPlaying = !isPlaying
                                    if (!isPlaying) {
                                        ShowMessage("暂停中，请稍后...")
                                        restoreDefaultInputMethod()
                                        ShowMessage("已暂停")
                                    }
                                }
                        )
                        FloatingLogButton(iconSize = iconSize, selected = showLogs) {
                            internalInteractionCount++
                            showLogs = !showLogs
                            logInteractionEnabled = false
                        }
                        if (showLogs) {
                            FloatingLogHistoryButton(iconSize = iconSize, selected = logInteractionEnabled) {
                                internalInteractionCount++
                                logInteractionEnabled = !logInteractionEnabled
                            }
                        }
                        FloatingAccountButtons(
                            iconSize = iconSize,
                            state = accountSwitchState,
                            enabled = !isSwitchingAccount && !GlobalVars.isAdPlaying,
                            onSwitchAccount = switchAccount
                        )
                        Image(
                            bitmap = settingIcon,
                            contentDescription = "Setting",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable(enabled = !isSwitchingAccount) {
                                    // Disable setting icon when ad is playing
                                    if (GlobalVars.isAdPlaying) return@clickable
                                    internalInteractionCount++
                                    pauseForInteraction()
                                    AppStateManager.setMode(AppMode.Main)
                                    restoreDefaultInputMethod()
                                    onOpenMainUI()
                                }
                        )
                        Image(
                            bitmap = exitIcon,
                            contentDescription = "Exit",
                            modifier = Modifier
                                .size(iconSize)
                                .padding(4.dp)
                                .clickable {
                                    // Exit immediately without a confirmation dialog.
                                    AppExitHelper.exitApplication(context)
                                }
                        )
                    }
                }
            }
        }
    )

    if (showLogs) {
        FloatingLogOverlay(
            logLines = logLines,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            interactive = logInteractionEnabled,
            onClear = ShowMessage::clearLogs,
            onDismiss = { showLogs = false }
        )
    }
}
