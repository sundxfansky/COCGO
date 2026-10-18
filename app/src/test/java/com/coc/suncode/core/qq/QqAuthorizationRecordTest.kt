package com.coc.suncode.core.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class QqAuthorizationRecordTest {
    private val response = """{"ret":"0","access_token":"access123","openid":"openid123","pay_token":"pay123","expires_in":"3600"}"""

    @Test
    fun grantExpirySurvivesPersistenceWithoutBeingExtended() {
        val grant = QqAuthorizationRecord.fromResponse(response, 1_000_000L)
        val restored = QqAuthorizationRecord.fromStored(grant.toJson())
        assertEquals(4_600_000L, restored.expiresAt)
        assertFalse(restored.isExpired(4_599_999L))
        assertTrue(restored.isExpired(4_600_000L))
        assertTrue(restored.isExpired(999_999L))
    }

    @Test
    fun outputMatchesCompanionsOrderAndTrailingDelimiter() {
        assertEquals(
            "access_token=access123&expires_in=3600&openid=openid123&pay_token=pay123&",
            QqAuthorizationRecord.fromResponse(response).toolInput()
        )
    }

    @Test
    fun rejectedOrIncompleteResponsesCannotBecomeGrants() {
        for (invalid in listOf(response.replace("\"ret\":\"0\"", "\"ret\":\"1\""), response.replace("\"pay_token\":\"pay123\",", ""))) {
            assertThrows(IllegalArgumentException::class.java) { QqAuthorizationRecord.fromResponse(invalid) }
        }
    }

    @Test
    fun delimiterInjectionAndInvalidDurationsAreRejected() {
        for (invalid in listOf(response.replace("access123", "abc&openid=other"), response.replace("3600", "-1"), response.replace("3600", "999999999999999999"))) {
            assertThrows(IllegalArgumentException::class.java) { QqAuthorizationRecord.fromResponse(invalid) }
        }
    }

    @Test
    fun diagnosticRepresentationDoesNotExposeCredentials() {
        val diagnostic = QqAuthorizationRecord.fromResponse(response).toString()
        assertFalse(diagnostic.contains("access123"))
        assertFalse(diagnostic.contains("openid123"))
        assertFalse(diagnostic.contains("pay123"))
    }
}
