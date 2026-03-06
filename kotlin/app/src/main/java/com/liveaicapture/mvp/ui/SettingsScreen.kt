package com.liveaicapture.mvp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.data.CaptureMode
import com.liveaicapture.mvp.data.GuideProvider

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var intervalText by remember(settings.intervalMs) { mutableStateOf(settings.intervalMs.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1424), Color(0xFF162D46), Color(0xFF24486D)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设置", color = Color.White)
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFD8EDFF))
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("账号")
                Text("${authState.user?.nickname ?: "未登录"}  ${authState.user?.email ?: ""}")
                Button(
                    onClick = { viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出登录")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("拍摄模式")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CaptureMode.entries.toList()) { mode ->
                        FilterChip(
                            selected = settings.captureMode == mode,
                            onClick = { viewModel.updateCaptureMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text("人像模式会固定按人像规则建议；通用模式会屏蔽人像识别。")
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("引导来源")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(GuideProvider.entries.toList()) { provider ->
                        FilterChip(
                            selected = settings.guideProvider == provider,
                            onClick = { viewModel.updateGuideProvider(provider) },
                            label = { Text(provider.label) },
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("分析间隔(ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("语音提示")
                    Switch(
                        checked = settings.voiceEnabled,
                        onCheckedChange = { viewModel.updateVoiceEnabled(it) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Debug 原始响应")
                    Switch(
                        checked = settings.debugEnabled,
                        onCheckedChange = { viewModel.updateDebugEnabled(it) },
                    )
                }

                Button(
                    onClick = {
                        viewModel.updateServerUrl(serverUrl)
                        viewModel.updateIntervalMs(intervalText.toLongOrNull() ?: settings.intervalMs)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存设置")
                }

                TextButton(
                    onClick = { viewModel.resetSettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复默认")
                }
            }
        }

        Text("当前场景：${uiState.detectedScene.label}", color = Color(0xFFD8EDFF))
    }
}
