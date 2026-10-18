package com.coc.suncode.jar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.pages.single.AccountDetailScreen
import com.coc.suncode.jar.ui.pages.single.HomeScreen
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.SchemaRegistry
import com.coc.suncode.jar.ui.schema.Scope
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use only in-memory drafts; these UI tests never log in, extract saves, or persist device data. */
@RunWith(AndroidJUnit4::class)
class AccountEditorTest {
    @get:Rule val compose = createComposeRule()
    private val originalStates = GlobalVars.configStates.toMap()
    private val originalMode = AppStateManager.currentMode
    private val wasLoaded = GlobalVars.isConfigLoaded
    private val autoRun = GlobalVars.isAutoRunEnabled

    @Before
    fun prepare() {
        GlobalVars.configStates.clear()
        SchemaRegistry.ALL_MODULES.forEach { module ->
            val indexes = if (module.scope == Scope.GLOBAL) 1..1 else 1..3
            indexes.forEach { index ->
                module.settings.forEach { def ->
                    val key = when (module.scope) {
                        Scope.GLOBAL -> def.key
                        Scope.ACCOUNT -> "${def.key}$index"
                        Scope.PROFILE -> "${def.key}_c$index"
                    }
                    GlobalVars.configStates[key] = mutableStateOf(def.defaultValue.toString())
                }
            }
        }
        GlobalVars.configStates[Schema.GLOBAL_SETTINGS.ACCOUNT_COUNT.key]!!.value = "3"
        (1..3).forEach {
            GlobalVars.configStates["${Schema.ACCOUNT_SETTINGS.REMARK.key}$it"]!!.value = "测试账号$it"
            GlobalVars.configStates["${Schema.ACCOUNT_SETTINGS.START_METHOD.key}$it"]!!.value = "1"
            GlobalVars.configStates["${Schema.MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c$it"]!!.value = "${10_000 + it}"
        }
        GlobalVars.isConfigLoaded = true
        GlobalVars.isAutoRunEnabled = false
        AppStateManager.setMode(AppMode.Main)
    }

    @After
    fun restore() {
        GlobalVars.configStates.clear()
        GlobalVars.configStates.putAll(originalStates)
        GlobalVars.isConfigLoaded = wasLoaded
        GlobalVars.isAutoRunEnabled = autoRun
        AppStateManager.setMode(originalMode)
    }

    @Test
    fun accountListHasReadOnlySingleLineRowsAndOpensSeparateEditor() {
        compose.setContent { MaterialTheme { EnterMainCode().ShowMainUI(LocalContext.current) {} } }
        compose.onNodeWithText("账号设置").performClick()
        compose.onNodeWithText("提取存档").assertIsDisplayed()
        val remark = compose.onNodeWithText("1 · 测试账号1")
        remark.assert(hasSetTextAction().not())
        val middle = remark.fetchSemanticsNode().boundsInRoot.center.y
        val rowControls = listOf(
            compose.onAllNodes(isToggleable())[0],
            compose.onAllNodesWithText("上号")[0],
            compose.onAllNodesWithText("配置")[0]
        )
        rowControls.forEach { assertTrue(abs(it.fetchSemanticsNode().boundsInRoot.center.y - middle) < 8f) }
        compose.onAllNodesWithText("配置")[0].performClick()
        compose.onNodeWithText("账号 1 / 3").assertIsDisplayed()
        compose.onNodeWithText("主页设置").assertDoesNotExist()
        compose.onNodeWithText("配置文件1").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭账号配置").performClick()
        compose.onNodeWithText("账号设置").assertIsDisplayed()
    }

    @Test
    fun arrowNavigationKeepsEditsIndependentAndMasterSwitchDisablesWorldControls() {
        compose.setContent { MaterialTheme { AccountDetailScreen(1, {}, {}, {}) } }
        compose.onNode(hasSetTextAction() and hasText("测试账号1")).performTextReplacement("新备注")
        compose.onNode(hasSetTextAction() and hasText("10001")).performTextReplacement("12345")
        compose.onNodeWithContentDescription("主世界总开关", useUnmergedTree = true).performClick()
        compose.onNodeWithText("12345").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一个账号").performClick()
        compose.onNodeWithText("账号 2 / 3").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("测试账号2")).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("10002")).assertIsDisplayed()
        compose.onNodeWithContentDescription("上一个账号").performClick()
        compose.onNode(hasSetTextAction() and hasText("新备注")).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("0", GlobalVars.configStates["${Schema.MAIN_BASE_SETTINGS.ENABLED.key}_c1"]!!.value)
            assertEquals("1", GlobalVars.configStates["${Schema.MAIN_BASE_SETTINGS.ENABLED.key}_c2"]!!.value)
            assertEquals("10002", GlobalVars.configStates["${Schema.MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c2"]!!.value)
        }
    }

    @Test
    fun kickCooldownDefaultsToThreeMinutesAndCanBeEditedWithoutManualSwitchMode() {
        compose.setContent { MaterialTheme { HomeScreen() } }
        compose.onNodeWithText("启动手动切号模式").assertDoesNotExist()
        compose.onNodeWithText("切号并计时等待").performScrollTo().assertIsDisplayed()
        val label = Schema.GLOBAL_SETTINGS.AFTER_KICK_WAIT_MINUTES.displayName
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("3") and hasAnySibling(hasText(label))).performTextReplacement("5")
        compose.runOnIdle {
            assertEquals("5", GlobalVars.configStates[Schema.GLOBAL_SETTINGS.AFTER_KICK_WAIT_MINUTES.key]!!.value)
        }
    }
}
