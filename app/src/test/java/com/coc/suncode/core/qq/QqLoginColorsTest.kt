package com.coc.suncode.core.qq

import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqLoginColorsTest {
    private fun frame(name: String): QqLoginColors.Frame {
        // Fixtures retain small pixel patches from device screenshots; all unrelated content is blacked out.
        val image = javaClass.getResourceAsStream("/qq-login/$name.png").use { ImageIO.read(it) }
        return QqLoginColors.detect(image.width, image.height, image::getRGB)
    }

    @Test
    fun capturedConsentAndLoginFramesSelectTheNextAction() {
        assertEquals(QqGameLogin.Control(389, 620, false, true), frame("consent").control)
        assertEquals(QqGameLogin.Control(280, 490, false, true), frame("unchecked").control)
        assertEquals(QqGameLogin.Control(794, 426, true, false), frame("ready").control)
    }

    @Test
    fun checkedButDisabledLoginWaitsWithoutTogglingOrResubmitting() {
        val waiting = frame("waiting")
        assertTrue(waiting.loginVisible)
        assertNull(waiting.control)
    }

    @Test
    fun villageAndLoadingFramesNeverSelectAButton() {
        val village = frame("village")
        assertNull(village.control)
        assertFalse(village.loginVisible)
        assertNull(QqLoginColors.detect(1280, 720) { _, _ -> 0 }.control)
        assertNull(QqLoginColors.detect(1280, 720) { _, _ -> 0x63ac22 }.control)
    }

    @Test
    fun differentAspectRatiosRequireTheFallbackInsteadOfStretchingCoordinates() {
        assertFalse(QqLoginColors.detect(720, 1280) { _, _ -> error("Must not sample") }.supported)
        assertFalse(QqLoginColors.detect(1280, 800) { _, _ -> error("Must not sample") }.supported)
    }
}
