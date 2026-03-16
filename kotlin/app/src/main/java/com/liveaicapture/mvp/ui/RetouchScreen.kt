package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.data.RetouchPreset
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

@Composable
fun RetouchScreen(
    viewModel: MainViewModel,
    onBackToCamera: () -> Unit,
    openFeedback: () -> Unit,
) {
    val context = LocalContext.current
    val retouchState by viewModel.retouchUiState.collectAsStateWithLifecycle()
    val feedbackState by viewModel.feedbackUiState.collectAsStateWithLifecycle()

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

    CamMatePage(
        title = "AI 修图",
        subtitle = "调整预设和强度后继续反馈流程",
        onBack = onBackToCamera,
    ) {
        item {
            SectionCard {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
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
                Text("修图预设")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RetouchPreset.entries) { preset ->
                        FilterChip(
                            selected = retouchState.preset == preset,
                            onClick = { viewModel.updateRetouchPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }

                Text("强度：${(retouchState.strength * 100).toInt()}%")
                Slider(
                    value = retouchState.strength,
                    onValueChange = { viewModel.updateRetouchStrength(it) },
                    valueRange = 0f..1f,
                )

                Text("场景参考：${retouchState.sceneHint.label}")
                if (retouchState.provider.isNotBlank() || retouchState.model.isNotBlank()) {
                    Text("服务商：${retouchState.provider}  模型：${retouchState.model}")
                }

                retouchState.errorMessage?.let { Text(it) }

                Button(
                    onClick = { viewModel.applyRetouch() },
                    enabled = !retouchState.applying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (retouchState.applying) "处理中..." else "开始 AI 修图")
                }

                Button(
                    onClick = { viewModel.continueWithRetouchedPhoto() },
                    enabled = !retouchState.previewBase64.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("使用修图结果继续")
                }

                TextButton(
                    onClick = { viewModel.continueWithOriginalPhoto() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("跳过修图，使用原图")
                }
            }
        }
    }
}
