package com.liveaicapture.mvp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.PublishEntrySheet
import okhttp3.Headers

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    openHome: () -> Unit,
    openCommunity: () -> Unit,
    openCamera: () -> Unit,
    openPublish: () -> Unit,
    openEditProfile: () -> Unit,
    openLikedPosts: () -> Unit,
    openPostDetail: (Int) -> Unit,
) {
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val communityState by viewModel.communityUiState.collectAsStateWithLifecycle()
    var showPublishSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeletePostId by rememberSaveable { mutableStateOf<Int?>(null) }

    val publishPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        showPublishSheet = false
        uri?.toString()?.let {
            viewModel.prepareDirectPublish(it)
            openPublish()
        }
    }

    LaunchedEffect(authState.authenticated, communityState.feed.size, communityState.loadingFeed) {
        if (
            authState.authenticated &&
            communityState.feed.isEmpty() &&
            !communityState.loadingFeed
        ) {
            viewModel.refreshCommunityFeed(reset = true)
        }
    }

    val user = authState.user
    val nickname = user?.nickname.orEmpty().ifBlank { "用户名" }
    val bio = user?.bio?.takeIf { it.isNotBlank() } ?: "暂时没有简介"
    val avatarUri = user?.avatarUri

    val myPosts = remember(communityState.feed, user?.id) {
        val currentUserId = user?.id ?: return@remember emptyList()
        communityState.feed.filter { it.userId == currentUserId }
    }
    val likedPosts = remember(communityState.feed) {
        communityState.feed.filter { it.likedByMe }
    }
    val pendingDeletePost = remember(communityState.feed, pendingDeletePostId) {
        communityState.feed.firstOrNull { it.id == pendingDeletePostId }
    }
    val postRows = myPosts.chunked(2)
    val likedCount = likedPosts.size
    val commentCount = myPosts.sumOf { it.commentCount }
    val postCount = myPosts.size

    Box(modifier = Modifier.fillMaxSize()) {
        CamMatePage(
            title = "",
            showHeader = false,
            horizontalPadding = 0.dp,
            bottomBar = {
                AppBottomNav(currentTab = AppRootTab.Settings) { tab ->
                    when (tab) {
                        AppRootTab.Capture -> openHome()
                        AppRootTab.Community -> openCommunity()
                        AppRootTab.Settings -> Unit
                    }
                }
            },
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 22.dp, end = 22.dp, top = 34.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        text = "我的",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(
                            nickname = nickname,
                            avatarUri = avatarUri,
                            size = 112.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = nickname,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = bio,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileStatItem(
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.FavoriteBorder,
                                    contentDescription = "点赞",
                                )
                            },
                            value = likedCount.toString(),
                            label = "点赞",
                            onClick = openLikedPosts,
                        )
                        ProfileStatItem(
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = "评论",
                                )
                            },
                            value = commentCount.toString(),
                            label = "评论",
                        )
                        ProfileStatItem(
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Article,
                                    contentDescription = "帖子",
                                )
                            },
                            value = postCount.toString(),
                            label = "帖子",
                        )
                        Button(
                            onClick = openEditProfile,
                            modifier = Modifier.weight(1.35f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "编辑资料",
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "编辑资料",
                                modifier = Modifier.padding(start = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "我的帖子",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "$postCount 条内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "最新发布",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    if (postRows.isEmpty()) {
                        EmptyProfilePosts()
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            postRows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { post ->
                                        ProfilePostTile(
                                            post = post,
                                            authHeader = communityState.authHeader,
                                            deleting = communityState.deletingPostIds.contains(post.id),
                                            modifier = Modifier.weight(1f),
                                            onClick = { openPostDetail(post.id) },
                                            onDeleteClick = { pendingDeletePostId = post.id },
                                        )
                                    }
                                    repeat(2 - row.size) {
                                        Spacer(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.9f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showPublishSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 96.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "发表",
            )
        }

        if (showPublishSheet) {
            PublishEntrySheet(
                title = "选择发表方式",
                onDismiss = { showPublishSheet = false },
                onPickFromGallery = { publishPicker.launch("image/*") },
                onOpenCamera = {
                    showPublishSheet = false
                    openCamera()
                },
            )
        }

        if (pendingDeletePost != null) {
            AlertDialog(
                onDismissRequest = { pendingDeletePostId = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletePostId?.let(viewModel::deletePost)
                            pendingDeletePostId = null
                        },
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletePostId = null }) {
                        Text("取消")
                    }
                },
                title = { Text("删除这条帖子？") },
                text = { Text("删除后，这条内容会同时从主页预览和社区列表中移除。") },
            )
        }
    }
}

@Composable
private fun ProfileAvatar(
    nickname: String,
    avatarUri: String?,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUri.isNullOrBlank()) {
            Text(
                text = nickname.take(1).ifBlank { "我" },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(avatarUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "用户头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ProfileStatItem(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyProfilePosts() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.9f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(24.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "待发布",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfilePostTile(
    post: CommunityPostItem,
    authHeader: String,
    deleting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
        modifier = modifier
            .aspectRatio(0.9f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "profile-post-${post.id}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.46f),
                        ),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = post.caption.ifBlank { post.placeTag.ifBlank { "我的帖子" } },
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clickable(enabled = !deleting, onClick = onDeleteClick),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.52f),
        ) {
            Text(
                text = if (deleting) "删除中" else "删除",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
