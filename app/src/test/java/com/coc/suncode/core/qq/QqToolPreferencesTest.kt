package com.coc.suncode.core.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class QqToolPreferencesTest {
    @Test
    fun updatingModePreservesUnrelatedTypedPreferences() {
        val result = QqToolPreferences.putString("<map><int name=\"other\" value=\"7\"/><string name=\"pd_key\">1</string></map>", "pd_key", "2")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(result)))
        assertEquals(1, document.getElementsByTagName("int").length)
        assertEquals("2", document.getElementsByTagName("string").item(0).textContent)
        assertEquals(1, document.getElementsByTagName("string").length)
    }

    @Test
    fun rawAuthorizationDelimitersRoundTripThroughXmlEscaping() {
        val raw = "access_token=abc&expires_in=3600&openid=def&pay_token=ghi&"
        val result = QqToolPreferences.putString(null, "raw_text", raw)
        assertTrue(result.contains("&amp;"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(result)))
        assertEquals(raw, document.getElementsByTagName("string").item(0).textContent)
    }

    @Test
    fun externalEntitiesCannotReadHostFiles() {
        val hostile = "<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><map><string name='x'>&x;</string></map>"
        assertThrows(Exception::class.java) { QqToolPreferences.putString(hostile, "pd_key", "2") }
    }
}
