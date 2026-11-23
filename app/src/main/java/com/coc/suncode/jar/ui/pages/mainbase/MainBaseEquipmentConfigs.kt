package com.coc.suncode.jar.ui.pages.mainbase

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.coc.suncode.jar.ui.components.ReorderableToggleGrid
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.details.priorityKey

@Composable
fun MainBaseEquipmentConfigs(index: Int) {
    val priorities = remember { Schema.MAIN_BASE_EQUIPMENT_PRIORITIES.all.associateBy { it.key } }
    // The order is applied independently within each hero's equipment tab.
    Schema.MAIN_BASE_EQUIPMENTS.heroGroups.forEach { (hero, items) ->
        Text(hero)
        ReorderableToggleGrid(index, items) { priorities[priorityKey(it)] }
    }
}
