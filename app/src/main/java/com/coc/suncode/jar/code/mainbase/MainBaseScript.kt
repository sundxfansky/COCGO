package com.coc.suncode.jar.code.mainbase


import com.coc.suncode.core.util.basic.delayWithMultiplier
import com.coc.suncode.jar.code.mainbase.attack.mainBaseAttack
import com.coc.suncode.jar.code.mainbase.attack.mainBaseTrainTroops
import com.coc.suncode.jar.code.mainbase.clan.joinClan
import com.coc.suncode.jar.code.mainbase.clan.donateToClan
import com.coc.suncode.jar.code.mainbase.clan.requestReinforcements
import com.coc.suncode.jar.code.mainbase.herohall.placeHeroBanners
import com.coc.suncode.jar.code.mainbase.others.mainBaseCheckTutorials
import com.coc.suncode.jar.code.mainbase.others.mainBaseRemoveObstacles
import com.coc.suncode.jar.code.mainbase.others.zoomSmallMainBase
import com.coc.suncode.jar.code.mainbase.research.mainBaseResearch
import com.coc.suncode.jar.code.universal.buildings.BaseType
import com.coc.suncode.jar.code.universal.buildings.upgrade.upgradeBuildings
import com.coc.suncode.jar.code.universal.buildings.walls.upgradeWalls
import com.coc.suncode.jar.code.universal.clickRightBottom
import com.coc.suncode.jar.code.universal.smalltools.enterMainBase

suspend fun playMainBase(): Boolean {
    if (!enterMainBase()) return false

    zoomSmallMainBase()
    if (!mainBaseTrainTroops()) return false
    if (!mainBaseAttack()) return false
    if (!mainBaseRemoveObstacles()) return false
    if (!joinClan()) return false
    if (!requestReinforcements()) return false
    if (!donateToClan()) return false
    if (!upgradeWalls(BaseType.Main)) return false
    if (!upgradeBuildings(BaseType.Main)) return false
    if (!mainBaseResearch()) return false
    if (!placeHeroBanners()) return false
    if (!mainBaseCheckTutorials()) return false
//    if (!upgradeGears()) return false
    return true
}