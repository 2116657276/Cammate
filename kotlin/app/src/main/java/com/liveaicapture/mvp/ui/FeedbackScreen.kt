package com.liveaicapture.mvp.ui

import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

@Composable
fun FeedbackScreen(
    viewModel: MainViewModel,
    finishToCamera: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.feedbackUiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.submitted) {
        if (state.submitted) {
            viewModel.finishFeedbackFlow()
            finishToCamera()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    CamMatePage(
        title = "拍摄反馈",
        subtitle = "你的反馈会帮助 CamMate 持续优化建议",
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
                        val uri = state.photoUri
                        if (!uri.isNullOrBlank()) {
                            imageView.setImageURI(Uri.parse(uri))
                        }
                    },
                )
            }
        }

        item {
            SectionCard {
                Text("场景：${state.scene.label} · ${if (state.isRetouched) "已修图" else "原图"}")
                Text("建议：${state.tipText}", style = MaterialTheme.typography.bodyLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { star ->
                        val filled = star <= state.rating
                        Text(
                            text = if (filled) "★" else "☆",
                            fontSize = 28.sp,
                            color = if (filled) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    (1..5).forEach { value ->
                        Button(onClick = { viewModel.updateFeedbackRating(value) }) {
                            Text(value.toString())
                        }
                    }
                }

                state.errorMessage?.let { Text(it, color = Color(0xFFB42318)) }

                Button(
                    onClick = { viewModel.submitFeedback() },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.submitting) "提交中..." else "完成并提交")
                }
            }
        }
    }
}
