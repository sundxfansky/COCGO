package com.coc.suncode.jar.ui.pages.single

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebConfigSnapshotTest {
    @Test
    fun migrationCopiesSharedProfileWithoutMutatingTheSavedSnapshot() {
        val saved = JsonParser.parseString("""{"account_count":"2","account_config1":"3","account_config2":"3","gold_requirement_c3":"350","new_module_key":"keep"}""").asJsonObject
        val result = webConfigSnapshot(saved)
        assertEquals("350", result.get("gold_requirement_c1").asString)
        assertEquals("350", result.get("gold_requirement_c2").asString)
        assertEquals("1", result.get("account_config1").asString)
        assertEquals("2", result.get("account_config2").asString)
        assertEquals("2", result.get("qq_authorization_slot2").asString)
        assertEquals("1", result.get("account_profiles_version").asString)
        assertEquals("keep", result.get("new_module_key").asString)
        assertFalse(saved.has("gold_requirement_c1"))
    }

    @Test
    fun independentAccountsKeepTheirOwnValues() {
        val saved = JsonParser.parseString("""{"account_count":"2","account_profiles_version":"1","gold_requirement_c1":"100","gold_requirement_c2":"900"}""").asJsonObject
        val result = webConfigSnapshot(saved)
        assertEquals("100", result.get("gold_requirement_c1").asString)
        assertEquals("900", result.get("gold_requirement_c2").asString)
        assertEquals(result, webConfigSnapshot(result))
    }
}
