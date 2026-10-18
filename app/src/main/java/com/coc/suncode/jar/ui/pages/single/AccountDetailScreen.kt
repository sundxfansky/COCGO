package com.coc.suncode.jar.ui.pages.single

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.pages.builderbase.BuilderBaseConfig
import com.coc.suncode.jar.ui.pages.mainbase.MainBaseConfig
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.jar.ui.schema.Schema
import kotlinx.coroutines.launch

/** A separate destination keeps the home tabs and account list out of this editor. */
@Composable
fun AccountDetailScreen(
    initialIndex: Int, onBack: () -> Unit,
    onNavigatePriority: (Int) -> Unit, onNavigateBuilderBasePriority: (Int) -> Unit
) {
    val count = GlobalVars.configStates[Schema.GLOBAL_SETTINGS.ACCOUNT_COUNT.key]?.value?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    var index by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(1, count)) }
    var world by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(index) { listState.scrollToItem(0) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = index > 1 && !saving, onClick = { index--; GlobalVars.isAutoRunEnabled = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一个账号")
            }
            Text("账号 $index / $count", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(enabled = index < count && !saving, onClick = { index++; GlobalVars.isAutoRunEnabled = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一个账号")
            }
            IconButton(enabled = !saving, onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "关闭账号配置") }
        }
        // Let identity controls scroll on short screens while keeping world switches reachable.
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            item { key(index) { AccountConfig(index) } }
            stickyHeader {
                PrimaryTabRow(selectedTabIndex = world) {
                    listOf(Schema.MAIN_BASE_SETTINGS.ENABLED, Schema.BUILDER_BASE_SETTINGS.ENABLED).forEachIndexed { selectedWorld, setting ->
                        val state = GlobalVars.configStates["${setting.key}_c$index"]
                        Tab(selected = world == selectedWorld, onClick = { world = selectedWorld }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Schema.getDisplayName(setting.key), style = MaterialTheme.typography.labelLarge)
                                Switch(
                                    checked = state?.value == "1",
                                    onCheckedChange = { state?.value = if (it) "1" else "0"; GlobalVars.isAutoRunEnabled = false },
                                    modifier = Modifier.scale(0.7f).semantics { contentDescription = "${setting.displayName}总开关" }
                                )
                            }
                        }
                    }
                }
            }
            if (world == 0) MainBaseConfig(index, true, {}, onNavigatePriority, showHeader = false)
            else BuilderBaseConfig(index, true, {}, onNavigateBuilderBasePriority, showHeader = false)
        }
        Row {
            CustomButton(text = if (saving) "正在保存..." else "保存配置", enable = !saving, onClick = {
                saving = true
                scope.launch { try { if (ConfigManager.persistConfigs()) onBack() } finally { saving = false } }
            })
            CustomButton(text = "返回账号列表", enable = !saving, onClick = onBack)
        }
    }
}
