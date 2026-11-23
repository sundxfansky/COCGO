package com.coc.suncode.jar.code.mainbase.herohall

import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.core.util.fileactions.LogHelper.logAndRestart
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.jar.code.colorschema.ColorSchema
import com.coc.suncode.jar.code.colorschema.MyColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseResearchColors
import com.coc.suncode.jar.ui.schema.details.MainBasePets
import com.coc.suncode.jar.ui.schema.details.priorityKey
import com.coc.suncode.jar.ui.schema.SettingDef
import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import android.graphics.Point
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.colors.findMultiColors
import com.coc.suncode.jar.code.universal.colors.findMultiColorsUntil
import com.coc.suncode.jar.code.universal.smalltools.StorageKeys
import com.coc.suncode.jar.code.universal.smalltools.checkMemoryFile
import com.coc.suncode.jar.code.universal.smalltools.getBooleanConfigRuntime
import com.coc.suncode.jar.code.universal.smalltools.writeMemory
import com.coc.suncode.jar.ui.schema.Schema

suspend fun upgradeGearsAndPets(): Boolean {
    val wantsGear = getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.UPGRADE_ALL_GEAR.key) ||
        getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.UPGRADE_WERA_GEAR.key)
    val wantsPets = getBooleanConfigRuntime(Schema.MAIN_BASE_SETTINGS.UPGRADE_PETS.key)
    if (!wantsGear && !wantsPets) return true

    val storageKey = StorageKeys.withAccountNumber(
        StorageKeys.UPGRADE_GEARS_AND_PETS, InGamesVars.currentAccountNumber
    )

    // Only equipment checks are subject to the eight-hour interval.
    val checkGear = wantsGear && checkMemoryFile(storageKey, 480)
    if (!checkGear && !wantsPets) return true

    return withHeroHall {
        if (checkGear && upgradeEquipmentsAction()) {
            writeMemory(storageKey, (System.currentTimeMillis() / 60_000).toString())
        }
        if (wantsPets) checkPetsInShop()
    }
}

private suspend fun checkPets() {
    val petColorMap = listOf(
        MainBasePets.LASSI to MyColors.Lassi,
        MainBasePets.ELECTRO_OWL to MyColors.ElectroOwl,
        MainBasePets.MIGHTY_YAK to MyColors.MightyYak,
        MainBasePets.UNICORN to MyColors.Unicorn,
        MainBasePets.FROSTY to MyColors.Frosty,
        MainBasePets.DIGGY to MyColors.Diggy,
        MainBasePets.POISON_LIZARD to MyColors.PoisonLizard,
        MainBasePets.PHOENIX to MyColors.Phoenix,
        MainBasePets.SPIRIT_FOX to MyColors.SpiritFox,
        MainBasePets.ANGRY_JELLY to MyColors.AngryJelly,
        MainBasePets.SNEEZY to MyColors.Sneezy,
        MainBasePets.GREEDY_RAVEN to MyColors.GreedyRaven
    )

    // Enabled pets ordered by the configured pet priority (lower rank upgrades first).
    val enabledPets = petColorMap.filter { getBooleanConfigRuntime(it.first.key) }
        .sortedBy { (pet, _) -> getConfigRuntime(priorityKey(pet)).toIntOrNull() ?: (MainBasePets.all.indexOf(pet) + 1) }

    if (enabledPets.isEmpty()) {
        ShowMessage("账号${InGamesVars.currentAccountNumber}，没有启用的战宠")
        return
    }

    ShowMessage("账号${InGamesVars.currentAccountNumber}，已启用 ${enabledPets.size} 个战宠")

    // Pass 1: scan all pages and collect affordable pets; the top-priority pet is upgraded as soon as it is seen.
    val affordable = mutableSetOf<SettingDef>()
    for (page in 0 until PET_PAGES) {
        val screenBuffer = ScreenCaptureManager.capture(asBitmap = false) as? ScreenCaptureManager.CaptureResult ?: logAndRestart("checkPets: Screen Capture Failed.")
        for ((pet, colorSchema) in enabledPets) {
            if (pet in affordable) continue
            val result = findAffordablePet(pet, colorSchema, screenBuffer) ?: continue
            if (pet == enabledPets.first().first) {
                if (tryUpgradePet(pet, result.x, result.y)) return
                continue
            }
            affordable += pet
        }
        if (page < PET_PAGES - 1) {
            TouchActions.swipe(800, 600, 180, 600)
            delayWithMultiplier(100)
        }
    }

    // Pass 2: scroll back to the first page and try the affordable pets in priority order.
    for ((pet, colorSchema) in enabledPets.filter { it.first in affordable }) {
        repeat(PET_PAGES) { TouchActions.swipe(180, 600, 1200, 600) }
        delayWithMultiplier(100)
        for (page in 0 until PET_PAGES) {
            val result = findMultiColors(colorSchema)
            if (result != null) {
                if (tryUpgradePet(pet, result.x, result.y)) return
                break
            }
            TouchActions.swipe(800, 600, 180, 600)
            delayWithMultiplier(100)
        }
    }
}

private const val PET_PAGES = 3

/** Returns the pet's position when it is visible and its upgrade cost is affordable. */
private suspend fun findAffordablePet(pet: SettingDef, colorSchema: ColorSchema, screenBuffer: ScreenCaptureManager.CaptureResult): Point? {
    val result = findMultiColors(schema = colorSchema, byteBuffer = screenBuffer, increment = 1) ?: return null
    val insufficientLeft = result.x
    val insufficientTop = result.y
    val insufficientRight = minOf(result.x + 100, screenBuffer.width - 1)
    val insufficientBottom = minOf(result.y + 40, screenBuffer.height - 1)

    val insufficientSchema = ColorSchema.rescope(
        MainBaseResearchColors.MainBaseResearchInsufficientColors, insufficientLeft, insufficientTop, insufficientRight, insufficientBottom
    )

    if (findMultiColors(byteBuffer = screenBuffer, schema = insufficientSchema, increment = 1) != null) {
        ShowMessage("账号${InGamesVars.currentAccountNumber}，跳过 ${pet.displayName}: 资源不足")
        return null
    }
    return result
}

/** Opens the pet and confirms the upgrade; returns false when the upgrade button is unavailable. */
private suspend fun tryUpgradePet(pet: SettingDef, x: Int, y: Int): Boolean {
    TouchActions.tap(x, y, delayTime = 500)
    val confirmUpgrade = findMultiColorsUntil(schemas = listOf(MyColors.ConfirmUpgradePet), duration = 1000)
    if (confirmUpgrade == null) {
        ShowMessage("账号${InGamesVars.currentAccountNumber}，跳过 ${pet.displayName}: 无法升级")
        return false
    }

    ShowMessage("账号${InGamesVars.currentAccountNumber}，升级 ${pet.displayName}")
    TouchActions.tap(confirmUpgrade.x, confirmUpgrade.y)
    clickRightBottom(1)
    return true
}

private suspend fun checkPetsInShop() {
    val petsIcon = findMultiColors(MyColors.PetsIconInHeroHall)
    if (petsIcon != null) {
        TouchActions.tap(petsIcon.x, petsIcon.y, delayTime = 500)
        val petsShopBanner = findMultiColorsUntil(schemas = listOf(MyColors.PetsShopInnerBanner), duration = 1000)
        if (petsShopBanner != null) {
            ShowMessage("已进入宠物店")
            delayWithMultiplier(200)
            checkPets()
        }
    }
}

internal suspend fun backToHeroHall() {
    // Stop retrying after 10 seconds to avoid getting stuck in an endless back loop.
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        val smithIcon = findMultiColors(MyColors.SmithOreIcon)
        if (smithIcon != null) {
            TouchActions.tap(1222, 80)
        }
        val petsIcon = findMultiColors(MyColors.PetsIconInHeroHall)
        if (petsIcon != null) {
            return
        }
        TouchActions.tap(1216, 622)
        delayWithMultiplier(500)
    }
}
