package com.coc.suncode.jar.ui.schema

import com.coc.suncode.jar.ui.schema.details.MainBasePets
import com.coc.suncode.jar.ui.schema.details.MainBaseTroopsAndSpells
import com.coc.suncode.jar.ui.schema.details.priorityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritySchemaTest {
    @Test
    fun everyResearchItemHasARankInListOrder() {
        val priorities = Schema.MAIN_BASE_RESEARCH_PRIORITIES.all
        assertEquals(MainBaseTroopsAndSpells.all.map(::priorityKey), priorities.map { it.key })
        assertEquals((1..priorities.size).toList(), priorities.map { it.defaultValue })
    }

    @Test
    fun everyPetHasARankInListOrder() {
        val priorities = Schema.MAIN_BASE_PET_PRIORITIES.all
        assertEquals(MainBasePets.all.map(::priorityKey), priorities.map { it.key })
        assertEquals(MainBasePets.all.map { it.displayName }, priorities.map { it.displayName })
        assertEquals("1", Schema.getDefaultValue("${priorityKey(MainBasePets.LASSI)}_c3"))
    }

    @Test
    fun priorityKeysDoNotCollideWithOtherSettings() {
        val keys = SchemaRegistry.ALL_MODULES.flatMap { it.settings }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(SchemaRegistry.ALL_MODULES.any { it.name == "MAIN_BASE_RESEARCH_PRIORITIES" && it.scope == Scope.PROFILE })
        assertTrue(SchemaRegistry.ALL_MODULES.any { it.name == "MAIN_BASE_PET_PRIORITIES" && it.scope == Scope.PROFILE })
    }
}
