package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.data.RetouchMode
import com.liveaicapture.mvp.data.RetouchPreset
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetouchScreen(
    viewModel: MainViewModel,
    onBackToCamera: () -> Unit,
    openFeedback: () -> Unit,
) {
    val context = LocalContext.current
    val retouchState by viewModel.retouchUiState.collectAsStateWithLifecycle()
    val feedbackState by viewModel.feedbackUiState.collectAsStateWithLifecycle()
    var showResultChoiceSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(feedbackState.visible) {
        if (feedbackState.visible) {
            openFeedback()
        }
    }
    LaunchedEffect(retouchState.errorMessage) {
        retouchState.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(retouchState.previewBase64) {
        if (!retouchState.previewBase64.isNullOrBlank()) {
            showResultChoiceSheet = true
        }
    }

    CamMatePage(
        title = "AI 修图",
        subtitle = "模板修图或自定义提示词二选一",
        onBack = onBackToCamera,
    ) {
        item {
            SectionCard {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    update = { imageView ->
                        val base64 = retouchState.previewBase64
                        if (!base64.isNullOrBlank()) {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            imageView.setImageBitmap(bitmap)
                        } else {
                            val uri = retouchState.originalPhotoUri
                            if (!uri.isNullOrBlank()) {
                                imageView.setImageURI(Uri.parse(uri))
                            }
                        }
                    },
                )
            }
        }

        item {
            SectionCard {
                if (retouchState.previewBase64.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("修图模式", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RetouchMode.entries.forEach { mode ->
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = retouchState.mode == mode,
                                    onClick = { viewModel.updateRetouchMode(mode) },
                                    label = {
                                        Text(
                                            text = mode.label,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (retouchState.mode == RetouchMode.TEMPLATE) {
                        Text("模板", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RetouchPreset.entries.forEach { preset ->
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = retouchState.preset == preset,
                                    onClick = { viewModel.updateRetouchPreset(preset) },
                                    label = {
                                        Text(
                                            text = preset.label,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = retouchState.customPrompt,
                            onValueChange = { viewModel.updateRetouchCustomPrompt(it) },
                            label = { Text("自定义提示词") },
                            placeholder = { Text("例如：去除杂乱背景并优化肤色与色温") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text("场景参考：${retouchState.sceneHint.label}")
                    if (retouchState.requestId.isNotBlank()) {
                        Text(
                            text = "请求ID：${retouchState.requestId}",
                            color = Color(0xFF64748B),
                        )
                    }

                    retouchState.errorMessage?.let { Text(it, color = Color(0xFFB42318)) }

                    val canSubmit = !retouchState.applying && (
                        retouchState.mode == RetouchMode.TEMPLATE ||
                            retouchState.customPrompt.isNotBlank()
                        )
                    Button(
                        onClick = { viewModel.applyRetouch() },
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (retouchState.applying) "处理中..." else "开始 AI 修图")
                    }

                    TextButton(
                        onClick = { viewModel.continueWithOriginalPhoto() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("跳过修图，使用原图")
                    }
                } else {
                    Text("修图已完成，请在弹窗中选择下一步", style = MaterialTheme.typography.titleMedium)
                    if (retouchState.requestId.isNotBlank()) {
                        Text(
                            text = "请求ID：${retouchState.requestId}",
                            color = Color(0xFF64748B),
                        )
                    }
                }
            }
        }
    }

    if (showResultChoiceSheet && !retouchState.previewBase64.isNullOrBlank()) {
        ModalBottomSheet(
            onDismissRequest = {},
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("修图已完成", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        showResultChoiceSheet = false
                        viewModel.restartRetouchFromOriginal()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("继续修图")
                }
                Button(
                    onClick = {
                        showResultChoiceSheet = false
                        viewModel.continueWithRetouchedPhoto()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("完成")
                }
                if (retouchState.requestId.isNotBlank()) {
                    Text(
                        text = "请求ID：${retouchState.requestId}",
                        color = Color(0xFF64748B),
                    )
                }
            }
        }
    }
}
