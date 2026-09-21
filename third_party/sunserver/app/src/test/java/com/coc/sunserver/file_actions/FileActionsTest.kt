package com.coc.sunserver.file_actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileActionsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun atomicWriteReplacesExistingFileAndCleansTemporaryArtifacts() {
        val file = temporary.newFile("config.json")
        file.writeText("old")
        assertEquals(3, FileActions.writeFile(file.path, "new"))
        assertEquals("new", file.readText())
        assertEquals(listOf("config.json"), temporary.root.list()?.toList())
    }
    @Test fun failedReplacementPreservesTarget() {
        val directory = temporary.newFolder("occupied")
        val child = directory.resolve("keep.txt").apply { writeText("original") }
        assertTrue(runCatching { FileActions.writeFile(directory.path, "new") }.isFailure)
        assertEquals("original", child.readText())
        assertEquals(listOf("occupied"), temporary.root.list()?.toList())
    }
}
