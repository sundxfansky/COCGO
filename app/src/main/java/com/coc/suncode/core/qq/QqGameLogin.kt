package com.coc.suncode.core.qq

import com.coc.suncode.core.qq.QqToolBackend.Companion.quote
import com.coc.suncode.core.util.basic.ShowMessage
import org.w3c.dom.Element
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Reset only the CN save data, then use explicit consent and QQ login controls. */
internal class QqGameLogin(
    private val backend: QqGameBackend,
    private val notify: (String) -> Unit = { ShowMessage(it, isChecking = false) }
) {
    suspend fun launchAndRequestLogin(record: QqAuthorizationRecord): Boolean {
        // Recheck after root preparation, since a grant can expire while its files are being written.
        if (record.isExpired()) throw IOException("QQ 授权已过期，未重置国服存档")
        backend.shell("am force-stop --user ${backend.user} ${QqToolBackend.GAME}")
        // The existing CN archive uses exactly these account directories; resource files stay intact.
        backend.clearGameAccountData()
        backend.shell("am start --user ${backend.user} -n ${QqToolBackend.GAME}/${QqToolBackend.GAME_ACTIVITY}")
        notify("国服已启动，正在自动完成协议与 QQ 登录")
        val path = "/data/local/tmp/sun-qq-login-${UUID.randomUUID()}.xml"
        var switched = false
        var gameWasForeground = false
        var lostGameAt = System.currentTimeMillis()
        var restartCount = 0
        val startedAt = System.nanoTime()
        var lastMatchedAt = startedAt
        var firstControlAt = 0L
        var lastTapAt = 0L
        var lastTarget: Control? = null
        var qqTapAt = 0L
        var qqAttempts = 0
        var loginGoneAt = 0L
        var colorLoginSeen = false
        var usedHierarchy = false
        try {
            return withTimeoutOrNull(120_000L) {
                while (true) {
                    val focus = backend.shell("dumpsys activity activities").filter {
                        it.contains("mResumedActivity") || it.contains("topResumedActivity")
                    }.joinToString()
                    if (focus.contains(QqToolBackend.ACTIVITY)) {
                        notify("已跳转 QQ 授权工具，停止登录页点击")
                        return@withTimeoutOrNull true
                    }
                    if (focus.contains(QqToolBackend.GAME)) {
                        gameWasForeground = true
                        lostGameAt = 0L
                        val frame = backend.findFastFrame()
                        if (frame.loginVisible) colorLoginSeen = true
                        val now = System.nanoTime()
                        var target = frame.control
                        if (target != null || frame.loginVisible) lastMatchedAt = now
                        // Loading frames must not trigger slow OCR/hierarchy scans in the fast polling loop.
                        if (target == null && qqTapAt == 0L && !frame.loginVisible &&
                            (!frame.supported || now - lastMatchedAt >= 6_000_000_000L)) {
                            usedHierarchy = true
                            target = findNextControl(path, allowSwitch = !switched)
                            lastMatchedAt = System.nanoTime()
                        }
                        if (qqTapAt != 0L) {
                            // The companion may return between polls. Once the login page disappears, stop tapping.
                            if (colorLoginSeen && frame.supported && !frame.loginVisible && target == null) {
                                if (loginGoneAt == 0L) loginGoneAt = now
                                if (now - loginGoneAt >= 500_000_000L) {
                                    notify("QQ 登录已发起，游戏已离开登录页，停止点击")
                                    return@withTimeoutOrNull true
                                }
                            } else loginGoneAt = 0L
                        }
                        if (target != null) {
                            // Debounce stale frames; a newly detected next step can be tapped immediately.
                            val cooldown = if (target.isQq) 1_000_000_000L else 350_000_000L
                            if ((target == lastTarget && now - lastTapAt < cooldown) || (target.isQq && qqAttempts >= 3)) {
                                delay(80)
                                continue
                            }
                            if (firstControlAt == 0L) firstControlAt = now
                            // The backend checks the target application's foreground ownership at injection time.
                            backend.tapControl(target)
                            lastTapAt = System.nanoTime()
                            lastTarget = target
                            if (target.isQq) {
                                if (qqTapAt == 0L) {
                                    qqTapAt = lastTapAt
                                    notify("已点击 QQ 登录，按钮操作用时 ${(lastTapAt - firstControlAt) / 1_000_000L} 毫秒，游戏启动后用时 ${(lastTapAt - startedAt) / 1_000_000L} 毫秒")
                                }
                                qqAttempts++
                            }
                            if (!target.isConsent && !target.isQq) switched = true
                        }
                    } else if (gameWasForeground && focus.isNotBlank() &&
                        (qqTapAt != 0L || backend.shell("pidof ${QqToolBackend.GAME} || true").any { it.isNotBlank() })) {
                        // An unrelated app taking focus is not proof of a successful QQ authorization.
                        notify("前台已切换到其他应用，停止 QQ 登录页点击")
                        return@withTimeoutOrNull false
                    } else if (restartCount == 0) {
                        // A startup crash can expose the bridge again; retry the launch without clearing data twice.
                        if (lostGameAt == 0L) lostGameAt = System.currentTimeMillis()
                        val restartDelay = if (gameWasForeground) 2_000L else 5_000L
                        if (System.currentTimeMillis() - lostGameAt >= restartDelay &&
                            backend.shell("pidof ${QqToolBackend.GAME} || true").all { it.isBlank() }) {
                            backend.shell("am start --user ${backend.user} -n ${QqToolBackend.GAME}/${QqToolBackend.GAME_ACTIVITY}")
                            restartCount++
                            gameWasForeground = false
                            lostGameAt = 0L
                            notify("国服启动中断，正在重新打开游戏")
                        }
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false
        } finally {
            if (usedHierarchy) runCatching { backend.shell("rm -f ${quote(path)}") }
        }
    }

    internal suspend fun findNextControl(path: String, allowSwitch: Boolean): Control? {
        backend.findVisualControl(allowSwitch)?.let { return it }
        // Retain native/accessibility controls as a fallback when the screenshot does not expose a target.
        return try {
            backend.shell("uiautomator dump --compressed ${quote(path)}")
            findControl(backend.readText(path), allowSwitch)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    internal data class Control(val x: Int, val y: Int, val isQq: Boolean, val isConsent: Boolean = false)
    internal data class VisualLabel(val text: String, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

    companion object {
        private val qqLabels = setOf("QQ登录", "QQ授权登录", "使用QQ登录")
        private val switchLabels = setOf("切换账号", "切换帐号")

        internal fun findVisualControl(
            labels: List<VisualLabel>, width: Int, height: Int, allowSwitch: Boolean,
            agreementChecked: Boolean? = null
        ): Control? {
            val normalized = labels.map { it to it.text.replace(Regex("\\s+"), "") }
            fun valid(label: VisualLabel): Boolean =
                label.confidence.isFinite() && label.confidence >= 0.85f &&
                    listOf(label.left, label.top, label.right, label.bottom).all { it.isFinite() } &&
                    label.left >= 0 && label.top >= 0 && label.right <= width && label.bottom <= height &&
                    label.right > label.left && label.bottom > label.top

            // Require the exact Tencent agreement title and both lower buttons before consenting.
            val agreement = normalized.any { (label, text) ->
                valid(label) && text == "用户与隐私相关协议指引" && label.bottom < height * 0.20f
            }
            if (agreement) {
                val agree = normalized.firstOrNull { (label, text) ->
                    valid(label) && text == "同意" && label.top > height * 0.72f && label.right < width * 0.55f
                }?.first
                val decline = normalized.firstOrNull { (label, text) ->
                    valid(label) && text == "不同意" && label.top > height * 0.72f && label.left > width * 0.45f
                }?.first
                if (agree != null && decline != null &&
                    abs((agree.top + agree.bottom) - (decline.top + decline.bottom)) < height * 0.08f) {
                    return Control(((agree.left + agree.right) / 2).toInt(), ((agree.top + agree.bottom) / 2).toInt(), false, true)
                }
            }
            // On the CN login screen the checkbox must be checked before the QQ button is enabled.
            val loginAgreement = normalized.firstOrNull { (label, text) ->
                valid(label) && text.contains("我已经仔细阅读并同意") &&
                    label.top > height * 0.58f && label.bottom < height * 0.78f
            }?.first
            val qqLogin = normalized.firstOrNull { (label, text) ->
                valid(label) && text in qqLabels &&
                    label.left > width * 0.48f && label.top > height * 0.48f && label.bottom < height * 0.72f
            }?.first
            if (loginAgreement != null && qqLogin != null && agreementChecked == false) {
                val checkboxX = (loginAgreement.left - width * 0.035f).toInt()
                val checkboxY = ((loginAgreement.top + loginAgreement.bottom) / 2).toInt()
                if (checkboxX in 0 until width && checkboxY in 0 until height) {
                    return Control(checkboxX, checkboxY, false, true)
                }
            }
            for (qq in listOf(true, false)) {
                if (!qq && !allowSwitch) continue
                val expected = if (qq) qqLabels else switchLabels
                for (label in labels) {
                    if (!valid(label)) continue
                    if (label.text.replace(Regex("\\s+"), "") !in expected) continue
                    return Control(((label.left + label.right) / 2).toInt(), ((label.top + label.bottom) / 2).toInt(), qq)
                }
            }
            return null
        }

        internal fun findControl(xml: String, allowSwitch: Boolean): Control? {
            val document = QqXml.parse(xml)
            val nodes = document.getElementsByTagName("node")
            val choices = (0 until nodes.length).map { nodes.item(it) as Element }
            // Prefer QQ login over a switch control when both are present.
            for (qq in listOf(true, false)) {
                if (!qq && !allowSwitch) continue
                val labels = if (qq) qqLabels else switchLabels
                for (node in choices) {
                    if (node.getAttribute("package") != QqToolBackend.GAME || node.getAttribute("enabled") != "true") continue
                    val text = node.getAttribute("text").replace(Regex("\\s+"), "")
                    val description = node.getAttribute("content-desc").replace(Regex("\\s+"), "")
                    if (text !in labels && description !in labels) continue
                    var clickable = node
                    while (clickable.getAttribute("clickable") != "true") {
                        val parent = clickable.parentNode as? Element ?: break
                        if (parent.tagName != "node" || parent.getAttribute("package") != QqToolBackend.GAME) break
                        clickable = parent
                    }
                    if (clickable.getAttribute("clickable") != "true" || clickable.getAttribute("enabled") != "true") continue
                    val bounds = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(clickable.getAttribute("bounds")) ?: continue
                    val (left, top, right, bottom) = bounds.destructured.toList().map { it.toInt() }
                    if (right > left && bottom > top) return Control(left + (right - left) / 2, top + (bottom - top) / 2, qq)
                }
            }
            return null
        }
    }
}

/** A narrow backend makes destructive reset ordering verifiable without a connected device. */
internal interface QqGameBackend {
    val user: Int
    suspend fun shell(command: String): List<String>
    suspend fun readText(path: String): String
    suspend fun clearGameAccountData()
    suspend fun findFastFrame(): QqLoginColors.Frame = QqLoginColors.Frame(supported = false)
    suspend fun tapControl(control: QqGameLogin.Control)
    suspend fun findVisualControl(allowSwitch: Boolean): QqGameLogin.Control? = null
}
