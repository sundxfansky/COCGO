package com.coc.suncode.core.ui.floatingwindows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coc.suncode.core.ui.theme.AppColors
import com.coc.suncode.interfaces.AccountSwitchState

@Composable
internal fun FloatingAccountButtons(
    iconSize: Dp,
    state: AccountSwitchState,
    enabled: Boolean,
    onSwitchAccount: (Int) -> Unit
) {
    Row {
        AccountArrow(iconSize, Icons.Default.ArrowUpward, "上一个账号", enabled && state.previousAccount != null) {
            state.previousAccount?.let(onSwitchAccount)
        }
        AccountArrow(iconSize, Icons.Default.ArrowDownward, "下一个账号", enabled && state.nextAccount != null) {
            state.nextAccount?.let(onSwitchAccount)
        }
    }
}

@Composable
private fun AccountArrow(iconSize: Dp, icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    // Match the existing floating log controls while making unavailable saves visibly disabled.
    Box(
        modifier = Modifier
            .size(iconSize)
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (enabled) AppColors.Azure else Color.Gray.copy(alpha = 0.5f))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White else Color.LightGray,
            modifier = Modifier.fillMaxSize().padding(4.dp)
        )
    }
}
