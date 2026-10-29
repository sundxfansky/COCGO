@file:Suppress("FunctionName")

package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.lazy.LazyListScope
import com.coc.suncode.jar.ui.pages.builderbase.BuilderBaseConfig
import com.coc.suncode.jar.ui.pages.mainbase.MainBaseConfig

fun LazyListScope.GameConfig(
    index: Int,
    isMainExpanded: Boolean,
    onToggleMainExpanded: () -> Unit,
    isBuilderBaseExpanded: Boolean,
    onToggleBuilderBaseExpanded: () -> Unit,
    onScrollToBottom: () -> Unit = {}
) {

    MainBaseConfig(
        index = index,
        isExpanded = isMainExpanded,
        onToggleExpanded = onToggleMainExpanded
    )
    BuilderBaseConfig(
        index = index,
        isExpanded = isBuilderBaseExpanded,
        onToggleExpanded = onToggleBuilderBaseExpanded,
        onScrollToBottom = onScrollToBottom
    )
}