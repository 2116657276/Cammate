package com.liveaicapture.mvp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
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

    var serverUrl by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var intervalText by rememberSaveable(settings.intervalMs) { mutableStateOf(settings.intervalMs.toString()) }
    var voiceEnabled by rememberSaveable(settings.voiceEnabled) { mutableStateOf(settings.voiceEnabled) }
    var debugEnabled by rememberSaveable(settings.debugEnabled) { mutableStateOf(settings.debugEnabled) }
    var guideProvider by rememberSaveable(settings.guideProvider.raw) { mutableStateOf(settings.guideProvider) }
    var captureMode by rememberSaveable(settings.captureMode.raw) { mutableStateOf(settings.captureMode) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1424), Color(0xFF162D46), Color(0xFF24486D)),
                ),
            )
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("设置", color = Color.White)
                TextButton(onClick = onBack) {
                    Text("返回", color = Color(0xFFD8EDFF))
                }
            }
        }

        item {
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
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("拍摄模式")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CaptureMode.entries) { mode ->
                            FilterChip(
                                selected = captureMode == mode,
                                onClick = { captureMode = mode },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text("人像模式会固定按人像规则建议；通用模式会屏蔽人像识别。")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("引导来源")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GuideProvider.entries) { provider ->
                            FilterChip(
                                selected = guideProvider == provider,
                                onClick = { guideProvider = provider },
                                label = { Text(provider.label) },
                            )
                        }
                    }
                }
            }
        }

        item {
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
                            checked = voiceEnabled,
                            onCheckedChange = { voiceEnabled = it },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Debug 原始响应")
                        Switch(
                            checked = debugEnabled,
                            onCheckedChange = { debugEnabled = it },
                        )
                    }

                    Button(
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            val parsedInterval = intervalText.toLongOrNull()?.coerceIn(300L, 5000L)
                                ?: settings.intervalMs
                            viewModel.saveSettings(
                                serverUrl = serverUrl,
                                intervalMs = parsedInterval,
                                voiceEnabled = voiceEnabled,
                                debugEnabled = debugEnabled,
                                guideProvider = guideProvider,
                                captureMode = captureMode,
                                onSaved = {
                                    saving = false
                                    onBack()
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (saving) "保存中..." else "保存并返回")
                    }

                    TextButton(
                        onClick = {
                            viewModel.resetSettings()
                            serverUrl = "http://10.0.2.2:8000"
                            intervalText = "1000"
                            voiceEnabled = true
                            debugEnabled = false
                            guideProvider = GuideProvider.CLOUD
                            captureMode = CaptureMode.AUTO
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("恢复默认")
                    }
                }
            }
        }

        item {
            Text("当前场景：${uiState.detectedScene.label}", color = Color(0xFFD8EDFF))
        }
    }
}
