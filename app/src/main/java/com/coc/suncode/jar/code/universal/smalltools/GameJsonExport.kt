package com.coc.suncode.jar.code.universal.smalltools

import android.os.Build
import android.os.Environment
import com.coc.suncode.core.util.basic.ClipboardHelper
import com.coc.suncode.core.util.basic.ClipboardText
import com.coc.suncode.core.util.basic.RunShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.fileactions.FileHelper
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.enterMainScreen
import com.coc.suncode.jar.code.universal.isInHomePage
import com.coc.suncode.jar.code.universal.recognizer.findButtonByText
import com.coc.suncode.jar.ui.schema.Schema
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.GsonBuilder
import com.google.gson.Strictness

private const val TOWN_HALL_ID = 1000001
private val gameJsonParser = GsonBuilder().setStrictness(Strictness.STRICT).create()

/** Refreshes the current account's local export once per day after a verified copy. */
suspend fun exportGameJson(): Boolean {
    if (!getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.EXPORT_GAME_JSON.key)) return true
    val supported = try {
        ClipboardHelper.isAvailable()
    } catch (_: NoClassDefFoundError) {
        false
    }
    if (!supported) {
        ShowMessage("导出游戏 JSON 需要更新宿主 APK")
        return true
    }
    val version = InGamesVars.currentGameVersion
    val account = InGamesVars.currentAccountNumber
    val key = StorageKeys.withAccountNumber("${StorageKeys.GAME_JSON_EXPORT}_${version.id}_", account)
    if (!checkMemoryFile(key, 1440)) return true

    val previous = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) ClipboardHelper.readClipboard()?.text else null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !enterMainScreen()) return false
    ShowMessage("账号$account，正在导出游戏数据")
    if (!openGameExportSettings(version)) {
        ShowMessage("未找到游戏设置入口")
        return closeGameExportSettings()
    }

    TouchActions.swipe(630, 515, 630, 90, delayTime = 450)
    delayWithMultiplier(350)
    val copyY = if (version == GameVersion.CN) 300 else 450
    val copyButton = findButtonByText(730, 210, 1190, 625, if (version == GameVersion.CN) "复制" else "Copy")
    val copyStartedAt = System.currentTimeMillis()
    TouchActions.tap(copyButton?.x ?: 910, copyButton?.y ?: copyY, delayTime = 650)
    val clipboard = ClipboardHelper.readClipboard()
    val export = freshGameJson(clipboard, copyStartedAt, previous)
    if (export != null) {
        val path = "${Environment.getExternalStorageDirectory().path}/sunFiles/exports/game-${version.name.lowercase()}-$account.json"
        if (FileHelper.writeJson(path, export.toString())) {
            writeMemory(key, (System.currentTimeMillis() / 60_000).toString())
            ShowMessage("游戏数据已导出到 $path")
        } else {
            ShowMessage("账号$account，游戏数据写入失败")
        }
    } else {
        ShowMessage("账号$account，未读取到本次复制的游戏 JSON")
    }
    return closeGameExportSettings()
}

private suspend fun closeGameExportSettings(): Boolean {
    // Android Back closes nested game settings, whose close buttons vary by version.
    for (attempt in 1..3) {
        if (isInHomePage()) return true
        if (!isGameAtFront()) break
        RunShell.runNoOutput("input keyevent KEYCODE_BACK")
        delayWithMultiplier(350)
    }
    return enterMainScreen()
}

private suspend fun openGameExportSettings(version: GameVersion): Boolean {
    if (version == GameVersion.CN) {
        TouchActions.tap(1230, 450)
    } else {
        TouchActions.tap(70, 80)
    }
    val settings = findMultiColorsUntil(schemas = listOf(MyColors.GameSettings), duration = 700, increment = 1)
    if (settings != null) {
        TouchActions.tap(settings.x, settings.y, delayTime = 400)
    } else if (version == GameVersion.CN) {
        return false
    } else {
        TouchActions.tap(1010, 60, delayTime = 400)
    }
    if (version == GameVersion.CN) TouchActions.tap(630, 610, delayTime = 400)
    else TouchActions.tap(640, 635, delayTime = 400)
    return true
}

internal fun freshGameJson(clipboard: ClipboardText?, copyStartedAt: Long, previous: String?): JsonObject? {
    if (clipboard == null) return null
    val timestamp = clipboard.timestampMillis
    val fresh = if (timestamp != null) timestamp >= copyStartedAt else clipboard.text != previous
    if (!fresh) return null
    return try {
        val parsed = gameJsonParser.fromJson(clipboard.text, JsonObject::class.java) ?: return null
        val buildings = parsed.get("buildings")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        // Require a genuine game export before replacing a previously saved snapshot.
        parsed.takeIf {
            buildings.any { entry ->
                if (!entry.isJsonObject) return@any false
                val building = entry.asJsonObject
                positiveInteger(building, "data") == TOWN_HALL_ID && positiveInteger(building, "lvl") != null
            }
        }
    } catch (_: JsonParseException) {
        null
    }
}

private fun positiveInteger(json: JsonObject, key: String): Int? =
    json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()?.takeIf { it > 0 }
