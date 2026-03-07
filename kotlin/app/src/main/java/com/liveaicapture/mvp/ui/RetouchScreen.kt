package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.data.RetouchPreset

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1426), Color(0xFF172A42), Color(0xFF284161)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AI修图", color = Color.White, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBackToCamera) {
                Text("返回相机", color = Color(0xFFD6EEFF))
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA10263D)),
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { imageView ->
                    val base64 = retouchState.previewBase64
                    if (!base64.isNullOrBlank()) {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        imageView.setImageBitmap(bitmap)
                    } else {
                        val uri = retouchState.originalPhotoUri
                        if (!uri.isNullOrBlank()) {
                            imageView.setImageURI(android.net.Uri.parse(uri))
                        }
                    }
                },
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA19344F)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("修图预设", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetouchPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = retouchState.preset == preset,
                            onClick = { viewModel.updateRetouchPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Text("强度：${(retouchState.strength * 100).toInt()}%", color = Color(0xFFD2E8FA))
                Slider(
                    value = retouchState.strength,
                    onValueChange = { viewModel.updateRetouchStrength(it) },
                    valueRange = 0f..1f,
                )

                Text(
                    text = "场景参考：${retouchState.sceneHint.label}",
                    color = Color(0xFFD2E8FA),
                )

                if (retouchState.provider.isNotBlank() || retouchState.model.isNotBlank()) {
                    Text(
                        text = "Provider: ${retouchState.provider}  Model: ${retouchState.model}",
                        color = Color(0xFF9CC6E5),
                    )
                }

                retouchState.errorMessage?.let {
                    Text(it, color = Color(0xFFFFB4AB))
                }

                Button(
                    onClick = { viewModel.applyRetouch() },
                    enabled = !retouchState.applying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (retouchState.applying) "处理中..." else "AI修图（暂未开放）")
                }

                Button(
                    onClick = { viewModel.continueWithRetouchedPhoto() },
                    enabled = !retouchState.previewBase64.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("使用修图结果并继续")
                }

                TextButton(
                    onClick = { viewModel.continueWithOriginalPhoto() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("跳过修图，使用原图继续", color = Color(0xFFD6EEFF))
                }
            }
        }
    }
}
