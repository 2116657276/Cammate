package com.liveaicapture.mvp.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.ui.components.CamMatePage

private data class CommunityPublishSceneOption(
    val raw: String,
    val label: String,
)

private val publishScenes = listOf(
    CommunityPublishSceneOption(raw = "general", label = "通用"),
    CommunityPublishSceneOption(raw = "portrait", label = "人像"),
    CommunityPublishSceneOption(raw = "landscape", label = "风景"),
    CommunityPublishSceneOption(raw = "food", label = "美食"),
    CommunityPublishSceneOption(raw = "night", label = "夜景"),
    CommunityPublishSceneOption(raw = "pet", label = "宠物"),
    CommunityPublishSceneOption(raw = "flower", label = "花草"),
)

@Composable
fun CommunityPublishScreen(
    viewModel: MainViewModel,
    onBackToCommunity: () -> Unit,
    onPublishedToCommunityHome: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val publishPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateDirectPublishImageUri(uri?.toString())
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    LaunchedEffect(state.publishSuccessPostId) {
        if (state.publishSuccessPostId != null) {
            viewModel.consumePublishSuccess()
            onPublishedToCommunityHome()
        }
    }

    BackHandler(onBack = onBackToCommunity)

    CamMatePage(
        title = "",
        showHeader = false,
        horizontalPadding = 0.dp,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onBackToCommunity),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 2.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                }

                PublishSingleImageSlot(
                    imageUri = state.publishImageUri,
                    onAddClick = { publishPicker.launch("image/*") },
                    onClick = { showDeleteDialog = true },
                )

                OutlinedTextField(
                    value = state.publishCaption,
                    onValueChange = { viewModel.updateDirectPublishCaption(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("添加标题") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "选择场景标签",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        publishScenes.forEach { scene ->
                            FilterChip(
                                selected = state.publishSceneType == scene.raw,
                                onClick = { viewModel.updateDirectPublishSceneType(scene.raw) },
                                label = { Text(scene.label) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.publishReviewText,
                    onValueChange = { viewModel.updateDirectPublishReviewText(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                    placeholder = { Text("添加正文") },
                    shape = RoundedCornerShape(22.dp),
                )

                Button(
                    onClick = { viewModel.publishDirectPost() },
                    enabled = !state.publishingDirect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = if (state.publishingDirect) "发布中..." else "发布动态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (showDeleteDialog && !state.publishImageUri.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateDirectPublishImageUri(null)
                        showDeleteDialog = false
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("删除这张图片") },
            text = { Text("移除后将不会出现在本次发布内容中。") },
        )
    }
}

@Composable
private fun PublishSingleImageSlot(
    imageUri: String?,
    onClick: () -> Unit,
    onAddClick: () -> Unit,
) {
    if (imageUri.isNullOrBlank()) {
        AddImageTile(onClick = onAddClick)
    } else {
        PublishImageTile(
            imageUri = imageUri,
            onClick = onClick,
        )
    }
}

@Composable
private fun AddImageTile(
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.6f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(24.dp),
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "添加图片",
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PublishImageTile(
    imageUri: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(imageUri) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        AsyncImage(
            model = request,
            contentDescription = "publish-image",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "删除",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
