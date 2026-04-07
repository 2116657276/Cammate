package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.liveaicapture.mvp.data.FeedbackUiState
import com.liveaicapture.mvp.data.SceneType
import com.liveaicapture.mvp.ui.components.CamMatePage

private data class PublishSceneOption(
    val raw: String,
    val label: String,
)

private val publishSceneOptions = listOf(
    PublishSceneOption(raw = "general", label = "\u901a\u7528"),
    PublishSceneOption(raw = "portrait", label = "\u4eba\u50cf"),
    PublishSceneOption(raw = "landscape", label = "\u98ce\u666f"),
    PublishSceneOption(raw = "food", label = "\u7f8e\u98df"),
    PublishSceneOption(raw = "night", label = "\u591c\u666f"),
    PublishSceneOption(raw = "pet", label = "\u5ba0\u7269"),
    PublishSceneOption(raw = "flower", label = "\u82b1\u8349"),
)

@Composable
fun FeedbackScreen(
    viewModel: MainViewModel,
    finishToCamera: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.feedbackUiState.collectAsStateWithLifecycle()
    var showThanksCard by remember { mutableStateOf(false) }

    LaunchedEffect(state.submitted) {
        if (state.submitted) {
            showThanksCard = true
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(showThanksCard) {
        if (showThanksCard) {
            delay(1200)
            showThanksCard = false
            viewModel.finishFeedbackFlow()
            finishToCamera()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CamMatePage(
            title = "",
            showHeader = false,
            horizontalPadding = 18.dp,
        ) {
            item {
                FeedbackPageHeader(onBack = onBack)
            }

            item {
                FeedbackPreviewCard(state = state)
            }

            item {
                FeedbackContent(
                    state = state,
                    onRatingChange = viewModel::updateFeedbackRating,
                    onReviewChange = viewModel::updateFeedbackReviewText,
                    onPublishEnabledChange = viewModel::updateFeedbackPublishEnabled,
                    onPlaceTagChange = viewModel::updateFeedbackPublishPlaceTag,
                    onPublishSceneTypeChange = viewModel::updateFeedbackPublishSceneType,
                    onSubmit = viewModel::submitFeedback,
                )
            }
        }

        AnimatedVisibility(
            visible = showThanksCard,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x4DF8F5EE)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ThanksCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedbackPageHeader(
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "\u62cd\u6444\u53cd\u9988",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "\u7528\u66f4\u7b80\u6d01\u7684\u65b9\u5f0f\u7ed9\u51fa\u8bc4\u4ef7\uff0c\u6210\u7247\u611f\u53d7\u4f1a\u66f4\u76f4\u63a5\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.padding(start = 12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            tonalElevation = 1.dp,
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "\u8fd4\u56de",
                )
            }
        }
    }
}

@Composable
private fun FeedbackPreviewCard(
    state: FeedbackUiState,
) {
    val shape = RoundedCornerShape(28.dp)
    val imageModel: Any? = state.photoBase64?.takeIf { it.isNotBlank() }?.let {
        android.util.Base64.decode(it, android.util.Base64.DEFAULT)
    } ?: state.photoUri
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 520.dp)
            .aspectRatio(state.photoAspectRatio.coerceIn(0.55f, 1.8f))
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = shape,
            ),
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = "反馈图片预览",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackTag(text = state.scene.displayName())
            FeedbackTag(text = if (state.isRetouched) "\u5df2\u4fee\u56fe" else "\u539f\u56fe")
        }
    }
}

@Composable
private fun FeedbackContent(
    state: FeedbackUiState,
    onRatingChange: (Int) -> Unit,
    onReviewChange: (String) -> Unit,
    onPublishEnabledChange: (Boolean) -> Unit,
    onPlaceTagChange: (String) -> Unit,
    onPublishSceneTypeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
            tonalElevation = 1.dp,
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "\u8fd9\u6b21\u6210\u7247\u4f60\u8fd8\u6ee1\u610f\u5417",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "\u70b9\u51fb\u661f\u661f\u5373\u53ef\u5b8c\u6210\u8bc4\u5206",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = ratingToneLabel(state.rating),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { star ->
                        val selected = star <= state.rating
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .clickable { onRatingChange(star) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (selected) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = "\u8bc4\u5206 $star",
                                    tint = if (selected) Color(0xFFE3A93C) else Color(0xFF98A4AF),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

                OutlinedTextField(
                    value = state.reviewText,
                    onValueChange = onReviewChange,
                    label = { Text("\u8865\u5145\u611f\u53d7\uff08\u53ef\u9009\uff09") },
                    placeholder = { Text("\u4e00\u53e5\u8bdd\u8bb0\u5f55\u8fd9\u6b21\u62cd\u6444\u4f53\u9a8c") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "\u540c\u6b65\u5230\u793e\u533a",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = "\u5f00\u542f\u540e\u4f1a\u5728\u63d0\u4ea4\u8bc4\u5206\u540e\u81ea\u52a8\u53d1\u5e03",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.publishToCommunity,
                    onCheckedChange = onPublishEnabledChange,
                )
            }

            AnimatedVisibility(visible = state.publishToCommunity) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.publishPlaceTag,
                        onValueChange = onPlaceTagChange,
                        label = { Text("\u5730\u70b9\u6807\u7b7e") },
                        placeholder = { Text("\u4f8b\u5982\uff1a\u5916\u6ee9\u3001\u68ee\u6797\u3001\u516c\u56ed") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        publishSceneOptions.forEach { scene ->
                            FilterChip(
                                selected = state.publishSceneType == scene.raw,
                                onClick = { onPublishSceneTypeChange(scene.raw) },
                                label = { Text(scene.label) },
                            )
                        }
                    }
                }
            }
        }

        state.errorMessage?.let {
            Text(
                text = it,
                color = Color(0xFFB42318),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        FilledTonalButton(
            onClick = onSubmit,
            enabled = !state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(if (state.submitting) "\u63d0\u4ea4\u4e2d..." else "\u63d0\u4ea4\u53cd\u9988")
        }
    }
}

@Composable
private fun FeedbackTag(
    text: String,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ThanksCard(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "CamMate",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "\u611f\u8c22\u60a8\u7684\u53cd\u9988",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "\u6b63\u5728\u8fd4\u56de\u62cd\u7167\u9875\u9762",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SceneType.displayName(): String = when (this) {
    SceneType.PORTRAIT -> "\u4eba\u50cf"
    SceneType.GENERAL -> "\u901a\u7528"
    SceneType.LANDSCAPE -> "\u98ce\u666f"
    SceneType.FOOD -> "\u7f8e\u98df"
    SceneType.NIGHT -> "\u591c\u666f"
    SceneType.PET -> "\u5ba0\u7269"
    SceneType.FLOWER -> "\u82b1\u8349"
}

private fun ratingToneLabel(rating: Int): String = when (rating.coerceIn(1, 5)) {
    1 -> "\u9700\u8981\u91cd\u62cd"
    2 -> "\u6709\u70b9\u53ef\u60dc"
    3 -> "\u4e2d\u89c4\u4e2d\u77e9"
    4 -> "\u633a\u6ee1\u610f"
    else -> "\u975e\u5e38\u559c\u6b22"
}
