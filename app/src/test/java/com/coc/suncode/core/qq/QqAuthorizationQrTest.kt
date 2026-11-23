package com.coc.suncode.core.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqAuthorizationQrTest {
    @Test
    fun acceptsTencentQrLinksWithoutOpeningThem() {
        assertTrue(QqAuthorizationQr.isQqLoginUrl("http://txz.qq.com/sample"))
        assertTrue(QqAuthorizationQr.isQqLoginUrl("https://ssl.ptlogin2.qq.com/sample"))
        assertFalse(QqAuthorizationQr.isQqLoginUrl("http://ssl.ptlogin2.qq.com/sample"))
        assertFalse(QqAuthorizationQr.isQqLoginUrl("http://txz.qq.com.example.org/sample"))
        assertFalse(QqAuthorizationQr.isQqLoginUrl("https://user@txz.qq.com/sample"))
        assertFalse(QqAuthorizationQr.isQqLoginUrl("http://txz.qq.com:8080/sample"))
    }
}
