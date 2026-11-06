package com.coc.suncode.core.data.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudConfigPolicyTest {
    @Test
    fun websiteAndApiAddressNormalizeToTheSameEndpoint() {
        assertEquals("https://settings.example/api/v1", CloudConfigPolicy.endpoint(" https://settings.example/ "))
        assertEquals("https://settings.example/api/v1", CloudConfigPolicy.endpoint("https://settings.example/api/v1/"))
        assertEquals("http://192.168.1.3:3280/api/v1", CloudConfigPolicy.endpoint("http://192.168.1.3:3280"))
    }

    @Test
    fun rejectsCredentialsAndNonWebSchemesInConnectionAddress() {
        listOf("file:///tmp/settings", "javascript:alert(1)", "https://user:secret@settings.example", "https://settings.example/?token=secret", "https://settings.example/#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudConfigPolicy.endpoint(it) }
        }
    }

    @Test
    fun revisionIgnoresObjectOrderButDetectsChangedValues() {
        val a = JsonParser.parseString("{\"one\":\"1\",\"two\":\"2\"}").asJsonObject
        val b = JsonParser.parseString("{\"two\":\"2\",\"one\":\"1\"}").asJsonObject
        assertEquals(CloudConfigPolicy.fingerprint(a), CloudConfigPolicy.fingerprint(b))
        b.addProperty("one", "changed")
        assertNotEquals(CloudConfigPolicy.fingerprint(a), CloudConfigPolicy.fingerprint(b))
    }

    @Test
    fun invalidRemoteConfigurationCannotReachPersistence() {
        listOf("{\"account_count\":\"0\"}", "{\"account_count\":\"10001\"}", "{\"account_count\":\"x\"}", "{\"toggle\":true}", "{\"nested\":{}}").forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudConfigPolicy.validate(JsonParser.parseString(it).asJsonObject) }
        }
        CloudConfigPolicy.validate(JsonObject())
        CloudConfigPolicy.validate(JsonParser.parseString("{\"account_count\":\"2\",\"toggle\":\"1\"}").asJsonObject)
    }
}
