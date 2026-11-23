@file:Suppress("ClassName")

package com.coc.suncode.jar.ui.schema.details

import com.coc.suncode.jar.ui.schema.SettingDef

/** Builds one priority setting per item; the default rank follows the item list order (1 = highest). */
private fun prioritiesFor(items: List<SettingDef>, category: String): List<SettingDef> =
    items.mapIndexed { i, item -> SettingDef(priorityKey(item), item.displayName, i + 1, category) }

/** The priority setting key paired with an item setting key. */
fun priorityKey(item: SettingDef): String = "${item.key}_priority"

object MainBaseEquipmentPriorities {
    val all = prioritiesFor(MainBaseEquipments.all, "MAIN_BASE_EQUIPMENT_PRIORITIES")
}

object MainBaseResearchPriorities {
    val all = prioritiesFor(MainBaseTroopsAndSpells.all, "MAIN_BASE_RESEARCH_PRIORITIES")
}

object MainBasePetPriorities {
    val all = prioritiesFor(MainBasePets.all, "MAIN_BASE_PET_PRIORITIES")
}
