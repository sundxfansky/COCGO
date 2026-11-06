package com.coc.suncode.jar.ui.schema

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebSchemaExportTest {
    @Test
    fun bundledWebSchemaUsesEveryCanonicalLabelAndDefault() {
        // Check the build-time exporter against the real Kotlin objects, including generated priorities.
        val snapshot = listOf(File("../web/src/schema.json"), File("web/src/schema.json"))
            .first { it.isFile }
        val modules = JsonParser.parseString(snapshot.readText()).asJsonObject.getAsJsonArray("modules")
        assertEquals(SchemaRegistry.ALL_MODULES.size, modules.size())
        SchemaRegistry.ALL_MODULES.zip(modules).forEach { (expected, actual) ->
            val module = actual.asJsonObject
            assertEquals(expected.name, module.get("name").asString)
            assertEquals(expected.scope.name, module.get("scope").asString)
            val settings = module.getAsJsonArray("settings")
            assertEquals(expected.settings.size, settings.size())
            expected.settings.zip(settings).forEach { (definition, entry) ->
                val field = entry.asJsonObject
                assertEquals(definition.key, field.get("key").asString)
                assertEquals(definition.displayName, field.get("displayName").asString)
                assertEquals(definition.defaultValue.toString(), field.get("defaultValue").asString)
                assertTrue(field.get("kind").asString in setOf("toggle", "number", "text", "select", "password"))
            }
        }
    }
}
