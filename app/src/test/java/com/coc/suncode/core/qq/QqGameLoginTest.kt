package com.coc.suncode.core.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class QqGameLoginTest {
    private fun grant(capturedAt: Long = System.currentTimeMillis()) = QqAuthorizationRecord("access", "openid", "pay", 3600, capturedAt)
    private fun node(text: String, pkg: String = "com.tencent.tmgp.supercell.clashofclans", clickable: Boolean = true): String =
        """<node package="$pkg" text="$text" enabled="true" clickable="$clickable" bounds="[10,20][110,80]"/>"""

    @Test
    fun qqEntryTakesPriorityOverAccountSwitch() {
        val target = QqGameLogin.findControl("<hierarchy>${node("切换账号")}${node("QQ 登录")}</hierarchy>", true)
        assertEquals(QqGameLogin.Control(60, 50, true), target)
    }

    @Test
    fun foreignPackagesAndGenericConsentAreNeverClicked() {
        assertNull(QqGameLogin.findControl("<hierarchy>${node("QQ登录", "other.app")}${node("同意")}${node("确定")}</hierarchy>", true))
    }

    @Test
    fun switchCannotBeClickedRepeatedly() {
        assertNull(QqGameLogin.findControl("<hierarchy>${node("切换账号")}</hierarchy>", false))
    }

    @Test
    fun labelCanResolveToClickableParent() {
        val xml = "<hierarchy>" + node("parent").replace("/>", ">${node("QQ登录", clickable = false)}</node>") + "</hierarchy>"
        assertEquals(QqGameLogin.Control(60, 50, true), QqGameLogin.findControl(xml, false))
    }

    @Test
    fun visualLoginRequiresAnExactConfidentLabelInsideTheScreen() {
        fun label(text: String, confidence: Float = 0.99f, right: Float = 110f) =
            QqGameLogin.VisualLabel(text, confidence, 10f, 20f, right, 80f)
        assertNull(QqGameLogin.findVisualControl(listOf(label("同意"), label("QQ登录", 0.4f), label("QQ登录", right = 1300f)), 1280, 720, true))
        assertEquals(QqGameLogin.Control(60, 50, true), QqGameLogin.findVisualControl(listOf(label("切换账号"), label("QQ 登录")), 1280, 720, true))
        assertNull(QqGameLogin.findVisualControl(listOf(label("切换账号")), 1280, 720, false))
    }

    @Test
    fun visualConsentRequiresTheExactAgreementAndBothChoices() {
        fun label(text: String, left: Float, top: Float, right: Float, bottom: Float, confidence: Float = 0.99f) =
            QqGameLogin.VisualLabel(text, confidence, left, top, right, bottom)
        val title = label("用户与隐私相关协议指引", 490f, 32f, 790f, 73f)
        val agree = label("同意", 365f, 602f, 413f, 639f)
        val decline = label("不同意", 828f, 602f, 909f, 639f)
        assertEquals(QqGameLogin.Control(389, 620, false, true),
            QqGameLogin.findVisualControl(listOf(title, agree, decline), 1280, 720, true))
        assertNull(QqGameLogin.findVisualControl(listOf(agree, decline), 1280, 720, true))
        assertNull(QqGameLogin.findVisualControl(listOf(title, agree), 1280, 720, true))
        assertNull(QqGameLogin.findVisualControl(listOf(title, agree.copy(confidence = 0.3f), decline), 1280, 720, true))
    }

    @Test
    fun loginAgreementIsCheckedBeforeTheQqButton() {
        fun label(text: String, left: Float, top: Float, right: Float, bottom: Float) =
            QqGameLogin.VisualLabel(text, 0.99f, left, top, right, bottom)
        val qq = label("QQ 登录", 750f, 411f, 839f, 442f)
        val agreement = label("我已经仔细阅读并同意", 324f, 468f, 547f, 499f)
        val labels = listOf(qq, agreement)
        assertEquals(QqGameLogin.Control(279, 483, false, true),
            QqGameLogin.findVisualControl(labels, 1280, 720, true, agreementChecked = false))
        assertEquals(QqGameLogin.Control(794, 426, true),
            QqGameLogin.findVisualControl(labels, 1280, 720, true, agreementChecked = true))
        assertEquals(QqGameLogin.Control(794, 426, true),
            QqGameLogin.findVisualControl(listOf(qq), 1280, 720, true, agreementChecked = false))
    }

    @Test
    fun expiredAuthorizationCannotClearOrStopTheGame() = runBlocking {
        val backend = FakeBackend()
        try {
            QqGameLogin(backend) {}.launchAndRequestLogin(grant(capturedAt = 1))
            throw AssertionError("Expired authorization was accepted")
        } catch (_: IOException) {
            assertTrue(backend.commands.isEmpty())
        }
    }

    @Test
    fun failedResetCannotLaunchTheGame() = runBlocking {
        val backend = FakeBackend(clearSucceeds = false)
        try {
            QqGameLogin(backend) {}.launchAndRequestLogin(grant())
            throw AssertionError("Failed reset was accepted")
        } catch (_: IOException) {
            assertTrue(backend.commands.contains("clear-save-only"))
            assertFalse(backend.commands.any { it.startsWith("am start") })
        }
    }

    @Test
    fun resetPrecedesLaunchAndUsesTheCurrentAndroidUser() = runBlocking {
        val backend = FakeBackend()
        assertTrue(QqGameLogin(backend) {}.launchAndRequestLogin(grant()))
        assertTrue(backend.commands[0].startsWith("am force-stop --user 10 "))
        assertEquals("clear-save-only", backend.commands[1])
        assertTrue(backend.commands[2].startsWith("am start --user 10 "))
        assertFalse(backend.commands.any { it.startsWith("pm clear") })
    }

    @Test
    fun visualControlAvoidsTheHierarchyDumpOnTheFastPath() = runBlocking {
        val visual = QqGameLogin.Control(790, 430, true)
        val backend = FakeBackend(visual = visual)
        assertEquals(visual, QqGameLogin(backend) {}.findNextControl("fixture.xml", true))
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun hierarchyStillFindsLoginWhenNoVisualControlMatches() = runBlocking {
        val backend = FakeBackend(hierarchy = "<hierarchy>${node("QQ 登录")}</hierarchy>")
        assertEquals(QqGameLogin.Control(60, 50, true),
            QqGameLogin(backend) {}.findNextControl("fixture.xml", true))
        assertTrue(backend.commands.single().startsWith("uiautomator dump"))
    }

    @Test
    fun cancelingHierarchyFallbackDoesNotResumeTheLoginLoop() = runBlocking {
        val backend = FakeBackend(dumpFailure = CancellationException("Canceled"))
        val failure = runCatching { QqGameLogin(backend) {}.findNextControl("fixture.xml", true) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test
    fun colorPathSkipsOcrAndStopsWhenTheCompanionTakesFocus() = runBlocking {
        val backend = FakeBackend(
            focusSequence = listOf(QqToolBackend.GAME, QqToolBackend.ACTIVITY),
            fastFrame = QqLoginColors.Frame(loginVisible = true, control = QqGameLogin.Control(794, 426, true))
        )
        assertTrue(QqGameLogin(backend) {}.launchAndRequestLogin(grant()))
        assertEquals(1, backend.taps.size)
        assertEquals(0, backend.visualCalls)
        assertFalse(backend.commands.any { it.startsWith("uiautomator") || it.startsWith("input") })
    }

    @Test
    fun unrelatedAppStopsClickingWithoutClaimingAuthorizationSuccess() = runBlocking {
        val backend = FakeBackend(
            focusSequence = listOf(QqToolBackend.GAME, "com.android.launcher"),
            fastFrame = QqLoginColors.Frame(loginVisible = true, control = QqGameLogin.Control(794, 426, true))
        )
        assertFalse(QqGameLogin(backend) {}.launchAndRequestLogin(grant()))
        assertEquals(1, backend.taps.size)
    }

    private class FakeBackend(
        private val clearSucceeds: Boolean = true,
        private val visual: QqGameLogin.Control? = null,
        private val hierarchy: String? = null,
        private val dumpFailure: Exception? = null,
        private val focusSequence: List<String> = listOf(QqToolBackend.ACTIVITY),
        private val fastFrame: QqLoginColors.Frame = QqLoginColors.Frame(supported = false)
    ) : QqGameBackend {
        override val user = 10
        val commands = mutableListOf<String>()
        val taps = mutableListOf<QqGameLogin.Control>()
        var visualCalls = 0
        private var focusIndex = 0
        override suspend fun shell(command: String): List<String> {
            commands += command
            if (command.startsWith("uiautomator") && dumpFailure != null) throw dumpFailure
            return when {
                command.startsWith("dumpsys") -> listOf("mResumedActivity: ${focusSequence[(focusIndex++).coerceAtMost(focusSequence.lastIndex)]}")
                else -> emptyList()
            }
        }
        override suspend fun findFastFrame(): QqLoginColors.Frame = fastFrame
        override suspend fun tapControl(control: QqGameLogin.Control) { taps += control }
        override suspend fun findVisualControl(allowSwitch: Boolean): QqGameLogin.Control? {
            visualCalls++
            return visual
        }
        override suspend fun readText(path: String): String = hierarchy ?: error("Unexpected hierarchy read")
        override suspend fun clearGameAccountData() {
            commands += "clear-save-only"
            if (!clearSucceeds) throw IOException("Cannot clear account save")
        }
    }
}
