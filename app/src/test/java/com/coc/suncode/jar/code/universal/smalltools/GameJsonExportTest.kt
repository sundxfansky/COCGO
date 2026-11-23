package com.coc.suncode.jar.code.universal.smalltools

import com.coc.suncode.core.util.basic.ClipboardText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GameJsonExportTest {
    private val valid = """{"buildings":[null,{"lvl":1,"data":1000002},{"data":1000001,"lvl":15}],"units":[]}"""

    @Test
    fun validatesFreshExportsAndPreservesFields() {
        assertEquals(valid, freshGameJson(ClipboardText(valid, 2000), 1000, null).toString())
        assertNotNull(freshGameJson(ClipboardText(valid, 2000), 1000, valid))
        assertNotNull(freshGameJson(ClipboardText(valid, null), 1000, "previous clipboard"))
    }

    @Test
    fun staleClipboardCannotOverwriteAnExport() {
        assertNull(freshGameJson(ClipboardText(valid, 999), 1000, null))
        assertNull(freshGameJson(ClipboardText(valid, null), 1000, valid))
    }

    @Test
    fun unrelatedAndMalformedClipboardContentsAreRejected() {
        val unrelated = listOf(
            "", "plain text", "[]", "{}", """{"buildings":"invalid"}""",
            """{"buildings":[{"data":1000001,"lvl":null}]}""",
            """{"buildings":[{"data":[1000001],"lvl":1}]}""",
            """{"buildings":[{"data":1000001,"lvl":2},]}"""
        )
        for (text in unrelated) assertNull(text, freshGameJson(ClipboardText(text, 2000), 1000, null))
    }
}
