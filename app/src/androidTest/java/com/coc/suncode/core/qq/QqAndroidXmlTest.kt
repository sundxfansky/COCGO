package com.coc.suncode.core.qq

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** JVM Xerces accepts features unavailable in Android's DOM implementation. */
@RunWith(AndroidJUnit4::class)
class QqAndroidXmlTest {
    @Test
    fun companionPreferencesRoundTripOnAndroid() {
        val raw = "access_token=fixture&expires_in=3600&openid=fixture&pay_token=fixture&"
        val updated = QqToolPreferences.putString("<map><int name='other' value='7'/></map>", "raw_text", raw)
        val document = QqXml.parse(updated)
        assertEquals(raw, document.getElementsByTagName("string").item(0).textContent)
        assertEquals(1, document.getElementsByTagName("int").length)
        assertThrows(IllegalArgumentException::class.java) {
            QqXml.parse("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><map>&x;</map>")
        }
    }

    @Test
    fun explicitGameLoginControlParsesOnAndroid() {
        val control = QqGameLogin.findControl(
            "<hierarchy><node package='com.tencent.tmgp.supercell.clashofclans' enabled='true' clickable='true' text='QQ 登录' bounds='[10,20][110,80]'/></hierarchy>",
            allowSwitch = false
        )
        assertNotNull(control)
        assertEquals(QqGameLogin.Control(60, 50, true), control)
    }
}
