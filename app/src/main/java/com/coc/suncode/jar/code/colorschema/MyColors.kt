package com.coc.suncode.jar.code.colorschema

import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseEquipmentColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseEquipmentColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseAttackColors
import com.coc.suncode.jar.code.colorschema.colorpackage.FeatureColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseAttackColors
import com.coc.suncode.jar.code.colorschema.colorpackage.IFeatureColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseTraining
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseTutorial
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseObstaclesRemovalColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseResearchColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseResearchColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseResourcesColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseTrainingColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseTutorial
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.IBuilderBaseUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.IUIColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseTraining
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseTutorial
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseObstaclesRemovalColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseResourcesColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseTrainingColors
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseTutorial
import com.coc.suncode.jar.code.colorschema.colorpackage.builderbase.BuilderBaseUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.UIColors
import com.coc.suncode.jar.code.colorschema.colorpackage.IUniversalUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.UniversalUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseUpgradeColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseResearchColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseResearchColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseResearchLevelColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseResearchLevelColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseAttackColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseAttackColors
import com.coc.suncode.jar.code.colorschema.colorpackage.clancapital.ClanCapitalTutorialColors
import com.coc.suncode.jar.code.colorschema.colorpackage.clancapital.IClanCapitalTutorialColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseClanColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseHeroHallColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseClanColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseHeroHallColors

import com.coc.suncode.jar.code.colorschema.colorpackage.clancapital.ClanCapitalColors
import com.coc.suncode.jar.code.colorschema.colorpackage.clancapital.IClanCapitalColors

import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseTournamentColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseTournamentColors

import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseClanWarColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseClanWarColors

import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.MainBaseShopColors
import com.coc.suncode.jar.code.colorschema.colorpackage.mainbase.IMainBaseShopColors

object MyColors : IMainBaseShopColors by MainBaseShopColors, IMainBaseClanWarColors by MainBaseClanWarColors, IMainBaseTournamentColors by MainBaseTournamentColors, IClanCapitalColors by ClanCapitalColors, IUIColors by UIColors, IFeatureColors by FeatureColors, IMainBaseTutorial by MainBaseTutorial, IBuilderBaseTutorial by BuilderBaseTutorial, IMainBaseTraining by MainBaseTraining,
    IBuilderBaseResourcesColors by BuilderBaseResourcesColors, IBuilderBaseObstaclesRemovalColors by BuilderBaseObstaclesRemovalColors, IBuilderBaseTrainingColors by BuilderBaseTrainingColors,
    IBuilderBaseUpgradeColors by BuilderBaseUpgradeColors, IBuilderBaseResearchColors by BuilderBaseResearchColors, IBuilderBaseAttackColors by BuilderBaseAttackColors, IUniversalUpgradeColors by UniversalUpgradeColors,
    IMainBaseUpgradeColors by MainBaseUpgradeColors, IMainBaseResearchColors by MainBaseResearchColors, IMainBaseResearchLevelColors by MainBaseResearchLevelColors,
    IClanCapitalTutorialColors by ClanCapitalTutorialColors, IMainBaseAttackColors by MainBaseAttackColors, IMainBaseClanColors by MainBaseClanColors, IMainBaseHeroHallColors by MainBaseHeroHallColors,
    IMainBaseEquipmentColors by MainBaseEquipmentColors {
    //Main Base Pets Colors
    val Lassi = ColorSchema.parse(
        175, 385, 1110, 665, "FA51FA", "15|-34|CEA79B,37|-33|AA604D,55|-7|4E322D,81|-6|694337,88|-57|DF6B8F,93|-75|FF85FF,135|-55|834D3F,135|-28|E88E6D,116|-17|C06350", 0, 0.9, "莱希"
    )
    val ElectroOwl = ColorSchema.parse(
        175, 385, 1110, 665, "E2CBB6", "22|-18|E3CDB7,48|-46|BC9562,101|-9|E3CBB5,106|-40|B79F6C,93|-76|BAB653,105|-83|857C4C,116|-19|7C503D,149|-12|8B5649,141|-5|8C584C", 0, 0.9, "闪枭"
    )
    val MightyYak = ColorSchema.parse(
        175, 385, 1110, 665, "9DB1BD", "-12|-43|82AEC5,64|-38|255BA1,56|-63|215DA4,66|-62|2A63AF,77|-41|2765B5,80|-16|2868B9,96|-7|2869BF,101|-15|2B7ADB,122|-41|2975DA", 0, 0.9, "大牦"
    )
    val Unicorn = ColorSchema.parse(
        175, 385, 1110, 665, "9DB1BD", "45|-8|C6B6B8,55|-24|D1C5CC,65|-50|D5CCD5,84|-62|D3CED9,105|-72|D7CACC,126|-56|E4DCE0,102|-39|D5DEEF,87|-15|CBC3CD,112|0|63CFFF", 0, 0.9, "独角"
    )
    val Frosty = ColorSchema.parse(
        175, 385, 1110, 665, "947366", "11|-17|89695C,42|-26|5C8593,72|-14|B8A5A5,101|-13|968D96,84|-20|D3BCBF,114|-7|507D98,77|-39|7E6467,91|-65|362E2E,109|-37|8F8486", 0, 0.9, "冰牙"
    )
    val Diggy = ColorSchema.parse(
        175, 385, 1110, 665, "AC5D7D", "7|-25|E87E92,28|-47|BBD5F6,44|-22|FE8696,80|-2|795D67,115|-12|8D6072,135|-36|D36E99,99|-49|FFA0B9,88|-61|FF9872,88|-89|FFB874", 0, 0.9, "地兽"
    )
    val PoisonLizard = ColorSchema.parse(
        175, 385, 1110, 665, "9DB1BD", "38|-26|44DBFF,76|-59|3A86FB,94|-81|52A9FF,96|-47|4397FF,83|-39|4082FF,96|-29|44A9FF,116|-39|4AA2FF,116|-70|0D0F52,116|-94|6FD2FF", 0, 0.9, "猛蜥"
    )
    val Phoenix = ColorSchema.parse(
        175, 385, 1110, 665, "964EFF", "18|-33|AC4AFF,46|-14|48B6FF,67|-52|8956FF,84|-61|53FFFF,104|-43|576276,130|-46|546173,117|-28|35444D,75|-61|50C0DC,68|-108|C14FFF", 0, 0.9, "凤凰"
    )
    val SpiritFox = ColorSchema.parse(
        175, 385, 1110, 665, "E3B23B", "28|-7|BE973C,60|-1|E8C48A,88|-1|FFFFCD,70|-33|FFE19E,58|-49|D6CC91,80|-70|8E5B1F,102|-59|0E0E0E,94|-47|FFFFCA,91|-11|FFF5AC", 0, 0.9, "灵狐"
    )
    val AngryJelly = ColorSchema.parse(
        175, 385, 1110, 665, "C271FF", "16|-21|DA7EFF,42|-42|FFACFF,59|-29|DE94FF,89|1|A941FF,127|-11|5228E0,133|-38|A54AF4,130|-66|CD53E4,72|-87|FFC7FF,69|-66|FFA8FF", 0, 0.9, "愤怒水母"
    )
    val Sneezy = ColorSchema.parse(
        175, 385, 1110, 665, "9DB1BD", "34|-36|7F5652,69|-41|C3A19C,92|-61|C9B6E8,113|-32|846CA1,128|-68|947BBE,141|-61|8A6160,121|-42|C8AFDD,89|-12|0D0D0D,66|-32|BA948D", 0, 0.9, "阿啾"
    )
    val GreedyRaven = ColorSchema.parse(
        175, 385, 1110, 665, "787272", "-1|-16|807C77,7|-37|205274,23|-39|43294C,42|-11|613761,63|-37|462C46,68|-54|582E54,82|-100|F8ABFD,103|-87|3E3E45,128|-77|312F2E", 0, 0.9, "贪婪渡鸦"
    )
    val ConfirmUpgradePet = ColorSchema.parse(
        806, 591, 993, 664, "89F8D5", "37|0|89F8D5,63|14|87EFCC,112|0|89F8D5,149|0|89F8D5,0|37|4AD3A2,103|13|87F0CD,127|9|88F2D0,112|37|4AD3A2,149|5|88F5D2", 0, 0.9, "确认升级战宠"
    )

    //Main Base Small Tutorial Colors (Not current Tutorial Colors)
    val MainBaseSmallTutorial = ColorSchema.parse(
        164, 77, 1064, 605, "21ABFE", "7|0|1FB9FE,14|0|1EBBFE,20|0|1EB5FE,27|0|21A8FE,0|21|2CC8F9,7|21|28C7F9,14|21|26C5F9,20|21|25C2F8,27|21|28C2F9", 0, 0.9, "教程朝下箭头"
    )
    val OuterPetIcon = ColorSchema.parse(
        167, 503, 1115, 628, "32AFD2", "8|0|42BFDC,16|0|3DB9D7,23|0|39AECF,31|0|0D6EB4,0|10|32AFD2,8|10|3FBCDA,16|10|187AAA,23|10|36B0D0,31|10|2FA6CA", 0, 0.9, "宠物店按钮"
    )
    val ClanCastleAddReinforcement = ColorSchema.parse(
        171, 510, 1122, 634, "67778F", "9|0|7F8EAA,18|0|8C94A5,26|0|ABB6BE,35|0|E2D1B9,0|20|545A67,9|20|6C7388,18|20|A4B4C6,26|20|DCE1E5,35|20|E4D1BB", 0, 0.9, "部落城堡教程"
    )
    val RemoteGuardsIcon = ColorSchema.parse(
        165, 502, 1133, 632, "2F4B72", "7|0|5C4E55,13|0|546370,19|0|1E81D6,26|0|525B66,0|18|634C4F,7|18|ACC6D4,13|18|C8E4F1,19|18|C6E5F5,26|18|97ADBF", 0, 0.9, "远程守卫图标"
    )
    val MeleeGuardsIcon = ColorSchema.parse(
        165, 502, 1133, 632, "56281F", "6|0|FFD451,12|0|FFE13E,17|0|BCFFC4,23|0|43BFFF,0|17|8E9AAA,6|17|7C899A,12|17|748294,17|17|91AABA,23|17|9BB5C2", 0, 0.9, "近战守卫图标"
    )

    val FinishCommission = ColorSchema.parse(
        1093, 96, 1250, 695, "92FFEB", "12|0|92FFEB,34|0|92FFEB,62|0|92FFEB,84|0|92FFEB,96|34|2CC97A,20|34|2CC97A,17|34|2CC97A,100|1|91FFEA,5|29|2CCA7B", 0, 0.9, "搞定按钮"
    )


    val CNCultivationAd = ColorSchema.parse(
        930, 126, 988, 186, "FFFFFF", "-12|2|7C75FF,-14|-10|948CFF,16|-10|968BFF,16|2|7C74FF,16|16|1313E4,0|19|1710DA,-12|19|1611D8,-5|9|F7F7F7,1|6|F6F6F6", 0, 0.9, "国服修仙广告"
    )

    val GoblinWorker2 = ColorSchema.parse(
        450, 10, 850, 80, "5FA5E8", "4|9|41B4A3,-5|10|43BCCD,-14|6|328F8A,-14|-1|1D6871,-10|-6|53D6C1,-2|-6|53EFE2,8|-10|34A990,2|-14|4CFBE3,8|-1|54DDDE", 0, 0.9, "哥布林工人2"
    )

    val QueenArcher4 = ColorSchema.parse(
        0, 590, 1200, 720, "618BEA", "4|0|88AFFA,9|0|772A3F,13|0|B14269,17|0|C14E76,0|27|8F3256,4|27|90314D,9|27|9F3A60,13|27|391221,17|27|A23B60", 0, 0.9, "弓箭女皇4"
    )

    val DragonDuke2 = ColorSchema.parse(
        0, 590, 1200, 720, "0E0A0B", "13|0|746690,26|0|1A1760,39|0|171253,52|0|9AA2EE,0|15|0A0511,13|15|725870,26|15|E1E8FF,39|15|9A99D1,52|15|D2A8AD", 0, 0.9, "飞龙公爵2"
    )

    val MainBaseWorker4 = ColorSchema.parse(
        450, 10, 850, 80, "687ECB", "7|0|9CBEEF,13|0|5F72CA,19|0|6371CE,26|0|93B4EB,0|11|7482C2,7|11|A3AABC,13|11|CAD3E2,19|11|C9D2E2,26|11|989EAF", 0, 0.9,
    )

    val MainBaseWorker5 = ColorSchema.parse(
        450, 10, 850, 80, "6888D5", "6|0|728DE0,12|0|748DE6,18|0|7CA6E9,24|0|637ACA,0|9|8F97A8,6|9|BAC3D2,12|9|CAD4E1,18|9|A3ACBD,24|9|22242D", 0, 0.9,
    )

    val MainBaseLightningWorker = ColorSchema.parse(
        450, 10, 850, 80, "D2E0FE", "7|0|7489D9,13|0|5073DF,19|0|BBD1FF,26|0|BCD0FE,0|8|A9BACF,7|8|50668A,13|8|495881,19|8|496386,26|8|A2B6C1", 0, 0.9,
    )

    val MainBaseLightningWorker2 = ColorSchema.parse(
        450, 10, 850, 80, "EDF3F9", "7|0|A2BBFB,14|0|6C8EF4,20|0|BFD3FC,27|0|E5EFFA,0|6|AAC5FF,7|6|8AABF0,14|6|6981CA,20|6|A8C6FE,27|6|A3C3FD", 0, 0.9,
    )

    val BuilderBaseWorker3 = ColorSchema.parse(
        518, 5, 1009, 78, "A0C0FA", "6|0|5660AB,13|0|6B7ADC,19|0|A7CDFD,25|0|7687E1,0|9|111E3A,6|9|5E73A0,13|9|9AB0CC,19|9|41517C,25|9|2B4D90", 0, 0.9,
    )


    val ShopInnerArrow2 = ColorSchema.parse(
        27, 264, 1236, 400, "00A3FD", "15|-21|20B3FF,30|-15|29BEFF,40|-8|2AB1FF,18|0|06A5FF,3|7|00A8FD,14|18|00A4FC,20|22|01A0FF,0|28|05ACFF,-8|9|01AAFE", 0, 0.9, "商店内部箭头2"
    )
    // Keep the classic worker icons available for accounts on the earlier layout.
    val MainBaseWorkerSchemas = listOf(
        MainBaseWorker, MainBaseWorker2, MainBaseWorker3, MainBaseWorker4, MainBaseWorker5,
        MainBaseLightningWorker, MainBaseLightningWorker2
    )
    val BuilderBaseWorkerSchemas = listOf(BuilderBaseWorker, BuilderBaseWorker2, BuilderBaseWorker3)
    val BattleRewardSchemas = listOf(BattleRewardSelection, BattleRewardSelection2)

    val NextOpponent2 = ColorSchema.parse(
        1065, 470, 1270, 565, "4CCBFF", "-96|-4|4CCBFF,-100|7|4CCBFF,-101|14|4CCBFF,-156|41|2676F5,-156|46|2676F5,-153|48|2676F5,-147|48|2676F5,-143|48|2676F5,-135|49|2676F5", 0, 0.9
    )

    val CanNotAttack = ColorSchema.parse(
        550, 262, 750, 444, "ABAFEB", "0|2|ADB1ED,0|5|ADB2EF,7|-8|1872AE,4|-21|083454,-3|3|A9AEEA,13|7|B2B6F3,2|-12|1B567F,7|-33|EBF0F2,5|-36|C3CBCE", 0, 0.95, "无法进攻"
    )

    val CanNotAttack2 = ColorSchema.parse(
        556, 366, 670, 451, "BDBEFE", "0|-2|BDBEFE,0|-5|BDBEFE,0|-8|BDBEFE,5|-4|BDBEFE,5|-1|B6B9F7,1|15|125584,-3|15|10466C,-2|-18|0A3655,2|-18|256E9C", 0, 0.95, "无法进攻2"
    )

    val CanNotAttack3 = ColorSchema.parse(
        636, 382, 691, 422, "C5C6FB", "0|1|C5C5F7,0|2|C6C6F6,0|3|C6C7F4,-14|3|C6C6FE,-15|3|C6C6FE,-17|3|BFBFFF,-18|10|C0C0FF,-17|10|BFBFFF,-16|10|C0C0FF", 0, 0.9, "无法进攻3"
    )

    val TroopColorAtDeploymentBar2 = ColorSchema.parse(
        0, 590, 1200, 720, "E29A56", "-2|0|E29A56,-4|0|E19A57,-6|0|E19A56,-8|0|E59A55,-5|8|FFFFFF,-5|10|FFFFFF,-3|10|FFFFFF,-2|12|FFFFFF,0|13|FFFFFF", 0, 0.95, "部队颜色2"
    )

    val DonateSword = ColorSchema.parse(
        0, 0, 0, 0, "EFE2DF", "-2|-2|AF8870,-4|-4|B08971,-1|1|C1977B,2|4|C9B3AA,4|7|D6C5C4,5|10|2D79A8,6|12|1A3C68,7|14|1D3E68,9|16|317398", 0, 0.9, "捐赠的剑"
    )

    val DonateHumanFigure = ColorSchema.parse(
        0, 0, 0, 0, "000000", "0|3|000000,1|10|000000,2|10|000000,2|23|B8733F,-7|21|A36540,11|21|CF8953,8|2|000000,-2|2|000000,0|-1|000000", 0, 0.9, "捐赠人影"
    )

    val TroopConfigCastleIcon = ColorSchema.parse(
        0, 0, 0, 0, "4B596D", "3|0|6D7C8E,7|0|495568,11|0|485465,14|0|495565,0|11|282F40,3|11|1D2026,7|11|1F2023,11|11|232326,14|11|8E9BA6", 0, 0.9
    )

    val ApproveJoinButton = ColorSchema.parse(
        5, 72, 482, 652, "2EBD76", "-1|-23|7DF3D8,-41|-21|78F0D3,-76|-23|7DF3D8,-76|2|30B66D,-211|2|1511DB,-203|-19|7571FF,-162|-23|807AFF,-130|-20|7573FF,-130|2|1511DB", 0, 0.9, "批准加入按钮"
    )
}
