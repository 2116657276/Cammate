package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import okhttp3.Headers

@Composable
fun AiComposeScreen(
    viewModel: MainViewModel,
    onBackToCapture: () -> Unit,
    onOpenCommunityPicker: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var localSceneUri by rememberSaveable { mutableStateOf<String?>(null) }

    val personPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCommunityPersonImageUri(uri?.toString())
    }
    val scenePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        localSceneUri = uri?.toString()
        viewModel.selectCommunityReferencePost(null)
    }

    LaunchedEffect(authState.authenticated) {
        if (!authState.authenticated) return@LaunchedEffect
        viewModel.refreshRecommendations()
        if (state.feed.isEmpty()) {
            viewModel.refreshCommunityFeed(reset = true)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    val recommendedPosts = remember(state.recommendations, state.feed) {
        state.recommendations.map { it.post }.ifEmpty { state.feed }.take(6)
    }
    val selectedCommunityPost = remember(state.referencePostId, state.recommendations, state.feed) {
        val selectedId = state.referencePostId ?: return@remember null
        state.recommendations.firstOrNull { it.post.id == selectedId }?.post
            ?: state.feed.firstOrNull { it.id == selectedId }
    }
    val showComposeDialog = state.composing ||
        state.composeJobStatus == "queued" ||
        state.composeJobStatus == "running" ||
        state.composeJobStatus == "failed" ||
        !state.composedPreviewBase64.isNullOrBlank()
    val isGenerating = state.composing || state.composeJobStatus == "queued" || state.composeJobStatus == "running"

    BackHandler(enabled = showComposeDialog && !isGenerating) {
        viewModel.clearComposedPreview()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ComposeHeader(
                    onBackToCapture = onBackToCapture,
                    modifier = Modifier.padding(top = 28.dp),
                )

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ComposeImageSlot(
                            modifier = Modifier.weight(1f),
                            imageModel = state.personImageUri,
                            authHeader = "",
                            placeholder = "人物图片",
                        )
                        ComposeImageSlot(
                            modifier = Modifier.weight(1f),
                            imageModel = selectedCommunityPost?.imageUrl ?: localSceneUri,
                            authHeader = if (selectedCommunityPost != null) state.authHeader else "",
                            placeholder = "场景图片",
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ComposePrimaryActionButton(
                            onClick = { personPicker.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.PhotoLibrary,
                            label = "选择人物图",
                            contentDescription = "从图库中选择人物图片",
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ComposeSecondaryActionButton(
                                onClick = { scenePicker.launch("image/*") },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.PhotoLibrary,
                                label = "本地场景图",
                                contentDescription = "选择本地场景图",
                            )
                            ComposeSecondaryActionButton(
                                onClick = onOpenCommunityPicker,
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.Forum,
                                label = "社区场景图",
                                contentDescription = "进入社区选择场景图",
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "为你推荐",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                        )
                        RecommendationStrip(
                            posts = recommendedPosts,
                            authHeader = state.authHeader,
                            selectedPostId = state.referencePostId,
                            onSelect = { postId ->
                                localSceneUri = null
                                viewModel.selectCommunityReferencePost(postId)
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Button(
                onClick = { viewModel.composeCommunityImage() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.76f)
                    .padding(bottom = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                SingleLineButtonText(
                    text = "开始生成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showComposeDialog) {
        ComposeProgressDialog(
            previewBase64 = state.composedPreviewBase64,
            progress = state.composeJobProgress.coerceIn(0, 100) / 100f,
            isGenerating = isGenerating,
            errorMessage = state.composeErrorMessage.takeIf { it.isNotBlank() },
            onSave = {
                viewModel.saveComposedPreviewToGallery { uri ->
                    if (uri.isNullOrBlank()) {
                        Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "已保存到系统图库", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onReturn = { viewModel.clearComposedPreview() },
        )
    }
}

@Composable
private fun ComposeHeader(
    onBackToCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "虚拟打卡",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(onClick = onBackToCapture) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ComposeImageSlot(
    modifier: Modifier,
    imageModel: String?,
    authHeader: String,
    placeholder: String,
) {
    val context = LocalContext.current
    val request = remember(imageModel, authHeader) {
        imageModel?.let { value ->
            ImageRequest.Builder(context)
                .data(value)
                .crossfade(true)
                .headers(
                    Headers.Builder().apply {
                        if (authHeader.isNotBlank() && value.startsWith("http")) {
                            add("Authorization", authHeader)
                        }
                    }.build(),
                )
                .build()
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.26f),
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (request == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = placeholder,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun ComposePrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    contentDescription: String,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
            SingleLineButtonText(label)
        }
    }
}

@Composable
private fun ComposeSecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    contentDescription: String,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 50.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
            SingleLineButtonText(label)
        }
    }
}

@Composable
private fun SingleLineButtonText(
    text: String,
    style: TextStyle? = null,
    fontWeight: FontWeight? = FontWeight.Medium,
) {
    Text(
        text = text,
        style = style ?: MaterialTheme.typography.labelLarge,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RecommendationStrip(
    posts: List<CommunityPostItem>,
    authHeader: String,
    selectedPostId: Int?,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { index ->
            val post = posts.getOrNull(index)
            if (post == null) {
                EmptyRecommendationSlot()
            } else {
                RecommendationThumbnail(
                    post = post,
                    authHeader = authHeader,
                    selected = post.id == selectedPostId,
                    onClick = { onSelect(post.id) },
                )
            }
        }
    }
}

@Composable
private fun EmptyRecommendationSlot() {
    Surface(
        modifier = Modifier.width(144.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(182.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun RecommendationThumbnail(
    post: CommunityPostItem,
    authHeader: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(post.imageUrl, authHeader) {
        ImageRequest.Builder(context)
            .data(post.imageUrl)
            .crossfade(true)
            .headers(
                Headers.Builder().apply {
                    if (authHeader.isNotBlank()) {
                        add("Authorization", authHeader)
                    }
                }.build(),
            )
            .build()
    }

    Box(
        modifier = Modifier
            .width(144.dp)
            .height(182.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
                },
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "recommendation-${post.id}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ComposeProgressDialog(
    previewBase64: String?,
    progress: Float,
    isGenerating: Boolean,
    errorMessage: String?,
    onSave: () -> Unit,
    onReturn: () -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 10.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            ),
                        ),
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = when {
                        isGenerating -> "生成中"
                        !previewBase64.isNullOrBlank() -> "虚拟打卡照片"
                        else -> "生成结果"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                LinearProgressIndicator(
                    progress = { if (!previewBase64.isNullOrBlank()) 1f else progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    isGenerating -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "正在生成虚拟打卡照片",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "系统会自动合成人物和场景，请稍等片刻",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    !previewBase64.isNullOrBlank() -> {
                        ComposeBase64Preview(
                            base64Data = previewBase64,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(304.dp),
                        )
                    }

                    else -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f),
                        ) {
                            Text(
                                text = errorMessage ?: "生成失败，请返回后重试",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }

                if (!isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!previewBase64.isNullOrBlank()) {
                            Button(
                                onClick = onSave,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                SingleLineButtonText("保存")
                            }
                        }
                        Button(
                            onClick = onReturn,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        ) {
                            SingleLineButtonText("返回")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeBase64Preview(
    base64Data: String,
    modifier: Modifier = Modifier,
) {
    val decoded = remember(base64Data) {
        try {
            android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
            },
            modifier = modifier
                .fillMaxWidth()
                .widthIn(min = 120.dp)
                .clip(RoundedCornerShape(20.dp)),
            update = { imageView ->
                if (decoded == null) {
                    imageView.setImageURI(Uri.EMPTY)
                    return@AndroidView
                }
                val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                imageView.setImageBitmap(bmp)
            },
        )
    }
}
