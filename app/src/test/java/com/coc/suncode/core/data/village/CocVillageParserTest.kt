package com.coc.suncode.core.data.village

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CocVillageParserTest {
    @Test
    fun parsesBothBasesAndPreservesUnknownIdsAndNestedLevels() {
        val result = CocVillageParser.parse("""
            {
              "tag":"#TEST123","timestamp":1792812432,"session":"private",
              "buildings":[
                {"data":1000001,"lvl":"17","weapon":3,"timer":120},
                {"data":1000097,"types":[{"data":103000011,"modules":[{"data":102000033,"lvl":0}]}]},
                {"data":1000009,"lvl":16,"cnt":32},
                null
              ],
              "buildings2":[{"data":1000034,"lvl":10}],
              "units2":[{"data":4000031,"lvl":10}],
              "skins":[52000268,99999999],
              "boosts":{"clocktower_boost":60,"unknown":999}
            }
        """.trimIndent())

        assertEquals("#TEST123", result.get("tag").asString)
        assertEquals(17, result.get("townHall").asInt)
        assertEquals(10, result.get("builderHall").asInt)
        assertFalse(result.has("session"))
        val rows = result.getAsJsonArray("items").map { it.asJsonObject }
            .associateBy { it.get("id").asInt }
        assertEquals(9, rows.size)
        assertEquals(120, rows.getValue(1000001).get("seconds").asInt)
        assertEquals(32, rows.getValue(1000009).get("count").asInt)
        assertEquals(0, rows.getValue(102000033).get("level").asInt)
        assertEquals(103000011, rows.getValue(102000033).get("parent").asInt)
        assertEquals("skins", rows.getValue(99999999).get("group").asString)
        assertEquals(1, result.getAsJsonObject("boosts").size())
    }

    @Test
    fun acceptsRegionalExportWithoutTagAndRejectsUnrelatedOrMalformedJson() {
        val result = CocVillageParser.parse("""{"buildings":[{"data":1000001,"lvl":12}]}""")
        assertFalse(result.has("tag"))
        assertFalse(result.has("timestamp"))
        assertThrows(IllegalArgumentException::class.java) { CocVillageParser.parse("""{"buildings":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { CocVillageParser.parse("""{buildings:[]}""") }
    }
}
