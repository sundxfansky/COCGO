package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.coc.suncode.BuildConfig
import com.coc.suncode.MainActivity
import com.coc.suncode.jar.ui.components.CloudConfigInput
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingSection

@Composable
fun CloudSettings() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("cloud", 0) }
    val defaultEndpoint = BuildConfig.BASE_URL
        .takeIf { it.isNotBlank() }
        ?.trimEnd('/')
        ?.plus("/api/v1")
        .orEmpty()
    var endpoint by remember { mutableStateOf(preferences.getString("base_url", defaultEndpoint) ?: defaultEndpoint) }
    var token by remember { mutableStateOf(preferences.getString("token", "") ?: "") }
    var message by remember { mutableStateOf("") }

    SettingSection {
        Text("云端配置")
        CloudConfigInput(
            label = "API地址",
            value = endpoint,
            onValueChange = { endpoint = it }
        )
        CloudConfigInput(
            label = "访问Token",
            value = token,
            onValueChange = { token = it }
        )
        CustomButton(
            text = "保存并连接",
            onClick = {
                preferences.edit()
                    .putString("base_url", endpoint.trimEnd('/'))
                    .putString("token", token.trim())
                    .apply()
                (context as? MainActivity)?.restartCloudSync()
                message = if (token.isBlank()) "已保存，未启用云端同步" else "已保存，开始同步"
            }
        )
        if (message.isNotBlank()) {
            Text(message, modifier = Modifier)
        }
    }
}
