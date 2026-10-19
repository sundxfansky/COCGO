package com.coc.suncode.core.qq

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.Process
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.jar.code.universal.ocr.PpOcrEngine
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** All companion data access is performed by the host's existing private root pipe. */
internal class QqToolBackend(private val context: Context) : QqGameBackend, QqReplayBackend {
    companion object {
        const val PACKAGE = "com.tencent.mobileqq"
        const val ACTIVITY = "com.tencent.open.agent.AgentActivity"
        const val GAME = "com.tencent.tmgp.supercell.clashofclans"
        const val GAME_ACTIVITY = "com.supercell.titan.tencent.GameAppTencent"
        private const val CERTIFICATE = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
        private const val BUNDLED_APK_ASSET = "qq/authorization-tool-v1.4.apk"
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }

    override val user: Int = Process.myUid() / 100_000
    override val externalInputPath: String get() = "${Environment.getExternalStorageDirectory().path}/aw_temp.txt"
    private var dataDir = ""
    private var uid = 0
    private var gameDataDir = ""

    @Suppress("DEPRECATION")
    suspend fun verifyTool() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(PACKAGE, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        } catch (_: PackageManager.NameNotFoundException) {
            installBundledTool()
            try {
                pm.getPackageInfo(PACKAGE, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            } catch (_: PackageManager.NameNotFoundException) {
                throw IOException("Root 安装已完成，但未检测到 QQ 授权工具")
            }
        }
        // Never stop or rewrite an official QQ installation which happens to share the package name.
        if (!hasToolSignature(info)) throw IOException("已安装的 QQ 包不是已分析的授权工具，未执行操作")
        val agent = pm.getActivityInfo(ComponentName(PACKAGE, ACTIVITY), 0)
        if (!agent.exported || !agent.enabled || !agent.applicationInfo.enabled) throw IOException("授权工具入口不可用")
        val application = pm.getApplicationInfo(PACKAGE, 0)
        dataDir = application.dataDir
        uid = application.uid
        // Grant only the verified companion's declared runtime permissions before opening its UI.
        grantToolPermissions(application.targetSdkVersion)
        ShowMessage.record("QQ 授权工具校验通过：uid=$uid，dataDir=$dataDir")
    }

    @Suppress("DEPRECATION")
    private suspend fun grantToolPermissions(targetSdk: Int) {
        val requested = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val permissions = mutableListOf<String>()
        // This companion targets Android 10, where storage runtime grants still apply on newer OSes.
        if (Build.VERSION.SDK_INT < 33 || targetSdk < 33) permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < 30 || targetSdk < 30) permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        }
        val declared = permissions.filter { it in requested }
        val commands = declared.flatMap { permission ->
            listOf(
                "pm clear-permission-flags --user $user $PACKAGE $permission user-set user-fixed",
                "pm grant --user $user $PACKAGE $permission"
            )
        } + "cmd appops set --user $user $PACKAGE POST_NOTIFICATION allow"
        shell(commands.joinToString(" && "))
        if (declared.any { context.packageManager.checkPermission(it, PACKAGE) != PackageManager.PERMISSION_GRANTED }) {
            throw IOException("Root 未能授予 QQ 授权工具权限，已停止启动")
        }
        ShowMessage.record("Root 已自动授予 QQ 授权工具的存储、媒体和通知权限")
    }

    /** Install the bundled companion only when its package is absent; never replace another QQ app. */
    private suspend fun installBundledTool() {
        ShowMessage("未安装 QQ 授权工具，正在通过 Root 安装内置版本", isChecking = false)
        QqToolInstaller(
            cacheDirectory = context.cacheDir,
            openApk = { context.assets.open(BUNDLED_APK_ASSET) },
            execute = { ServerShell.exec(it) }
        ).install(user)
        ShowMessage.record("Root 已安装内置 QQ 授权工具")
    }

    @Suppress("DEPRECATION")
    private fun hasToolSignature(info: PackageInfo): Boolean {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.singleOrNull()?.let { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } == CERTIFICATE
        } == true
    }

    @Suppress("DEPRECATION")
    suspend fun clearAuthorizationCache() {
        val info = try {
            context.packageManager.getPackageInfo(
                PACKAGE, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        // Clear the verified companion's preferences and WebView sessions, never an official QQ app.
        if (info != null && hasToolSignature(info)) {
            shell("am force-stop --user $user $PACKAGE")
            if (shell("pm clear --user $user $PACKAGE").none { it.trim() == "Success" }) {
                throw IOException("QQ 授权工具缓存清除失败，请重试")
            }
        }
        // This higher-priority input survives uninstalling the companion and must be removed separately.
        val path = quote(externalInputPath)
        shell("rm -f -- $path && [ ! -e $path ]")
    }

    fun verifyGame() {
        try {
            val activity = context.packageManager.getActivityInfo(ComponentName(GAME, GAME_ACTIVITY), 0)
            if (!activity.enabled || !activity.applicationInfo.enabled) throw IOException("国服游戏未启用")
            val path = activity.applicationInfo.dataDir
            val expected = "/data/user/$user/$GAME"
            if (path != expected && !(user == 0 && path == "/data/data/$GAME")) {
                throw IOException("国服游戏数据目录异常，未重置存档")
            }
            gameDataDir = path
        } catch (_: PackageManager.NameNotFoundException) {
            throw IOException("未安装匹配的部落冲突国服，未重置任何存档数据")
        }
    }

    override suspend fun clearGameAccountData() {
        if (gameDataDir.isEmpty()) throw IOException("国服游戏未经校验，未重置存档")
        // Match the CN save archive's two directories; leave files, cache and external resources alone.
        val preferences = quote(File(gameDataDir, "shared_prefs").path)
        val databases = quote(File(gameDataDir, "databases").path)
        val directory = quote(gameDataDir)
        shell("[ -d $directory ] && [ ! -L $directory ] && " +
            "for target in $preferences $databases; do " +
            "[ ! -L \"\$target\" ] && rm -rf -- \"\$target\" || exit 1; " +
            "done")
    }

    suspend fun prepareCapture() {
        stopTool()
        putPreference("ms", "pd_key", "2")
    }

    suspend fun prepareReplay(record: QqAuthorizationRecord) {
        QqAuthorizationReplay(this).prepare(record)
    }

    override suspend fun stopTool() {
        check(dataDir.isNotEmpty())
        shell("am force-stop --user $user $PACKAGE")
    }

    override suspend fun putPreference(name: String, key: String, value: String) {
        val directory = "$dataDir/shared_prefs"
        val path = "$directory/$name.xml"
        val exists = action(mapOf("subAction" to "check_exists", "path" to path)) == "true"
        val current = if (exists) readText(path) else null
        shell("mkdir -p ${quote(directory)} && chown $uid:$uid ${quote(directory)} && chmod 700 ${quote(directory)}")
        val updated = QqToolPreferences.putString(current, key, value)
        writeText(path, updated)
        // Atomic replacement preserves ordinary metadata; restore the app-specific SELinux label explicitly.
        shell("chown $uid:$uid ${quote(path)} && chmod 600 ${quote(path)} && restorecon ${quote(directory)} ${quote(path)}")
        // Do not launch the companion if its mode or selected account was not actually persisted.
        if (readText(path) != updated) throw IOException("授权工具设置写入校验失败，未继续登录")
    }

    override suspend fun shell(command: String): List<String> {
        val result = ServerShell.exec(command)
        if (!result.isSuccess) throw IOException("Root 操作失败，请检查 Root 权限及设备兼容性")
        return result.out
    }

    override suspend fun readText(path: String): String = action(mapOf("subAction" to "read", "path" to path))

    override suspend fun findFastFrame(): QqLoginColors.Frame {
        val bitmap = ScreenCaptureManager.capture() as? Bitmap ?: return QqLoginColors.Frame(supported = false)
        return try {
            QqLoginColors.detect(bitmap.width, bitmap.height, bitmap::getPixel)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun tapControl(control: QqGameLogin.Control) {
        // Reuse the host's persistent input worker, which verifies the foreground package before DOWN.
        TouchActions.selectTargetPackage(GAME)
        TouchActions.touchDown(control.x.toFloat(), control.y.toFloat(), 1)
        try {
            delay(20)
        } finally {
            withContext(NonCancellable) { TouchActions.touchUp(1) }
        }
    }

    override suspend fun findVisualControl(allowSwitch: Boolean): QqGameLogin.Control? {
        val bitmap = ScreenCaptureManager.capture() as? Bitmap ?: return null
        try {
            // Use the bundled OCR model for surface-rendered controls without logging screen text.
            PpOcrEngine.initialize(context)
            val labels = mutableListOf<QqGameLogin.VisualLabel>()
            fun scan(top: Int, bottom: Int) {
                val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                try {
                    labels += PpOcrEngine.recognize(crop).map {
                        QqGameLogin.VisualLabel(it.text, it.confidence, it.box.left, it.box.top + top, it.box.right, it.box.bottom + top)
                    }
                } finally {
                    crop.recycle()
                }
            }
            // Prioritize the title and lower controls to avoid recognizing the dense agreement body on every pass.
            val titleBottom = (bitmap.height * 0.20f).toInt()
            val controlsTop = (bitmap.height * 0.54f).toInt()
            scan(0, titleBottom)
            scan(controlsTop, bitmap.height)
            val checked = agreementChecked(bitmap)
            var target = QqGameLogin.findVisualControl(
                labels, bitmap.width, bitmap.height, allowSwitch, checked
            )
            if (target == null) {
                // Preserve support for a switch/login control placed in the middle of a different game screen.
                scan(titleBottom, controlsTop)
                target = QqGameLogin.findVisualControl(labels, bitmap.width, bitmap.height, allowSwitch, checked)
            }
            return target.also {
                if (it?.isConsent == true) ShowMessage.record("QQ 登录：已定位国服协议确认控件")
                else if (it != null) ShowMessage.record("QQ 登录：已通过画面识别定位明确入口")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ShowMessage.record("QQ 登录：画面识别暂不可用，继续等待文字入口")
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun agreementChecked(bitmap: Bitmap): Boolean {
        // The CN login checkbox is at 22% x / 68% y; its green tick distinguishes it from the empty box.
        val x0 = (bitmap.width * 0.205f).toInt()
        val x1 = (bitmap.width * 0.235f).toInt()
        val y0 = (bitmap.height * 0.655f).toInt()
        val y1 = (bitmap.height * 0.705f).toInt()
        var greenPixels = 0
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val pixel = bitmap.getPixel(x, y)
                val green = Color.green(pixel)
                if (green > Color.red(pixel) * 1.15f && green > Color.blue(pixel) * 1.1f && green > 85) {
                    greenPixels++
                }
            }
        }
        return greenPixels >= 12
    }

    override suspend fun writeText(path: String, content: String) {
        action(mapOf("subAction" to "write", "path" to path, "content" to content))
    }

    private suspend fun action(fields: Map<String, String>): String {
        val actions = GlobalVars.serverActions ?: throw IOException("本地 Root 功能尚未就绪")
        val response = actions.sendActionSync(fields + ("actionType" to "file_action"))
        if (response?.get("status")?.asString != "success") throw IOException("授权工具文件操作失败")
        return response.get("data")?.asString.orEmpty()
    }
}
