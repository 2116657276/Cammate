package com.liveaicapture.mvp.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF182B43), Color(0xFF29496A)),
                ),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("拍摄反馈", color = Color.White, style = MaterialTheme.typography.titleLarge)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xB0142A44)),
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { imageView ->
                    val uri = state.photoUri
                    if (!uri.isNullOrBlank()) {
                        imageView.setImageURI(android.net.Uri.parse(uri))
                    }
                },
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA17344F)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "场景：${state.scene.label} · ${if (state.isRetouched) "已修图" else "原图"}",
                    color = Color.White,
                )
                Text(
                    text = "建议：${state.tipText}",
                    color = Color(0xFFD0E8FC),
                    fontSize = 13.sp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { star ->
                        val filled = star <= state.rating
                        Text(
                            text = if (filled) "★" else "☆",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (filled) Color(0xFFFFD66E) else Color(0xFF8EA9C5),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .background(Color.Transparent)
                                .padding(2.dp),
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

                state.errorMessage?.let {
                    Text(it, color = Color(0xFFFFB4AB))
                }

                Button(
                    onClick = { viewModel.submitFeedback() },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.submitting) "提交中..." else "完成并提交评分")
                }
            }
        }
    }
}
