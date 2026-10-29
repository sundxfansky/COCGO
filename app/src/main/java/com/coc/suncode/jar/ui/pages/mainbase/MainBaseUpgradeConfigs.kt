package com.coc.suncode.jar.ui.pages.mainbase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.coc.suncode.jar.ui.components.CustomButton
import androidx.compose.runtime.remember
import com.coc.suncode.jar.ui.components.ReorderableToggleGrid
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.Schema.MAIN_BASE_BUILDINGS
import androidx.compose.runtime.saveable.rememberSaveable
import com.coc.suncode.jar.ui.components.InputRowWithCheckBox
import com.coc.suncode.jar.ui.schema.Schema.MAIN_BASE_SETTINGS

@Composable
fun MainBaseUpgradeConfigs(index: Int) {
    val items = MAIN_BASE_BUILDINGS.all
    // Building ranks are matched by display name, as the upgrade runtime does.
    val priorities = remember { Schema.MAIN_BASE_BUILDING_PRIORITIES.all.associateBy { it.displayName } }

    val isExpanded = rememberSaveable { mutableStateOf(true) }

    // 一键全选
    val selectAll = {
        items.forEach { item ->
            val key = "${item.key}_c${index}"
            if (!GlobalVars.configStates.containsKey(key)) {
                GlobalVars.configStates[key] = mutableStateOf("1")
            } else {
                GlobalVars.configStates[key]!!.value = "1"
            }
        }
    }

    // 一键反选
    val invertSelection = {
        items.forEach { item ->
            val key = "${item.key}_c${index}"
            val currentState = GlobalVars.configStates[key]!!.value
            val newValue = if (currentState == "1") "0" else "1"
            if (!GlobalVars.configStates.containsKey(key)) {
                GlobalVars.configStates[key] = mutableStateOf(newValue)
            } else {
                GlobalVars.configStates[key]!!.value = newValue
            }
        }
    }
    Column {
        Row {
            CustomButton(text = "一键全选", onClick = selectAll)
            CustomButton(text = "一键反选", onClick = invertSelection)
            CustomButton(text = if (isExpanded.value) "缩起" else "展开", onClick = { isExpanded.value = !isExpanded.value })
        }

        AnimatedVisibility(visible = isExpanded.value) {
            Column {
                ReorderableToggleGrid(index, items) { item -> priorities[item.displayName] }
            }
        }
        InputRowWithCheckBox(checkBoxKey = "${MAIN_BASE_SETTINGS.INSTANT_UPGRADE.key}_c$index", inputKey = "${MAIN_BASE_SETTINGS.INSTANT_UPGRADE_THRESHOLD.key}_c$index")
    }
}