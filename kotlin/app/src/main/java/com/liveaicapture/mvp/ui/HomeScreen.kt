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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import okhttp3.Headers

private data class CaptureCommunityPreview(
    val id: Int,
    val imageUrl: String? = null,
    val authHeader: String = "",
    val title: String,
    val placeholderColors: List<Color>,
)

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    openCamera: () -> Unit,
    openPoseRecommend: () -> Unit,
    openAiCompose: () -> Unit,
    openCommunity: () -> Unit,
    openSettings: () -> Unit,
) {
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val communityState by viewModel.communityUiState.collectAsStateWithLifecycle()

    LaunchedEffect(authState.authenticated, communityState.feed.size, communityState.loadingFeed) {
        if (
            authState.authenticated &&
            communityState.feed.isEmpty() &&
            !communityState.loadingFeed
        ) {
            viewModel.refreshCommunityFeed(reset = true)
        }
    }

    val homePreviews = communityState.feed
        .filter { it.imageUrl.isNotBlank() }
        .take(6)
        .map { post -> post.toCapturePreview(authHeader = communityState.authHeader) }
        .ifEmpty { defaultCapturePreviews() }

    CaptureHomeContent(
        communityPreviews = homePreviews,
        openCamera = openCamera,
        openPoseRecommend = openPoseRecommend,
        openAiCompose = openAiCompose,
        openCommunity = openCommunity,
        openSettings = openSettings,
    )
}

@Composable
private fun CaptureHomeContent(
    communityPreviews: List<CaptureCommunityPreview>,
    openCamera: () -> Unit,
    openPoseRecommend: () -> Unit,
    openAiCompose: () -> Unit,
    openCommunity: () -> Unit,
    openSettings: () -> Unit,
) {
    val firstRow = communityPreviews.take(2)
    val remainingRows = communityPreviews.drop(2).chunked(2)

    CamMatePage(
        title = "",
        showHeader = false,
        horizontalPadding = 0.dp,
        bottomBar = {
            AppBottomNav(currentTab = AppRootTab.Capture) { tab ->
                when (tab) {
                    AppRootTab.Capture -> Unit
                    AppRootTab.Community -> openCommunity()
                    AppRootTab.Settings -> openSettings()
                }
            }
        },
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                HomeHeroSection()

                CaptureActionRow(
                    openAiCompose = openAiCompose,
                    openCamera = openCamera,
                    openPoseRecommend = openPoseRecommend,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "\u793e\u533a\u7cbe\u9009\u63a8\u8350",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    CommunityPreviewRow(
                        items = firstRow,
                        openCommunity = openCommunity,
                    )
                }
            }
        }
        remainingRows.forEach { rowItems ->
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CommunityPreviewRow(
                        items = rowItems,
                        openCommunity = openCommunity,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeroSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(286.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF4ECE0),
                        Color(0xFFE2D2BC),
                        Color(0xFFD5E0DD),
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color(0xFF1E2A35).copy(alpha = 0.18f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 6.dp)
                .size(178.dp)
                .clip(CircleShape)
                .background(Color(0x33B8843A)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 54.dp, top = 24.dp)
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
                .size(132.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(Color(0x221E2A35)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFFF9F0E1).copy(alpha = 0.9f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WbSunny,
                        contentDescription = null,
                        tint = Color(0xFFD9A257),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Cammate",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB57A30),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 18.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Cammate\n\u966a\u4f60\u62cd\u4e0b\u6bcf\u4e00\u79cd\u5c0f\u7f8e\u597d",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFF17212B),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "\u4e0d\u662f\u6bcf\u4e00\u523b\u90fd\u5b8c\u7f8e\uff0c\u4f46\u6bcf\u4e00\u523b\u90fd\u503c\u5f97\u88ab\u6e29\u67d4\u8bb0\u5f55",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF31414D).copy(alpha = 0.92f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroInfoPill(text = "\u573a\u666f\u611f\u77e5")
                HeroInfoPill(text = "\u5149\u5f71\u5efa\u8bae")
                HeroInfoPill(text = "\u81ea\u7136\u4fee\u56fe")
            }
        }
    }
}

@Composable
private fun HeroInfoPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.64f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF253340),
        )
    }
}

@Composable
private fun CaptureActionRow(
    openAiCompose: () -> Unit,
    openCamera: () -> Unit,
    openPoseRecommend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            HomeActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = "\u865a\u62df\u6253\u5361",
                icon = Icons.Outlined.AutoAwesome,
                onClick = openAiCompose,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier.weight(1.1f),
            contentAlignment = Alignment.Center,
        ) {
            CapturePrimaryButton(onClick = openCamera)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            HomeActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = "\u62cd\u6444\u63a8\u8350",
                icon = Icons.Outlined.TipsAndUpdates,
                onClick = openPoseRecommend,
            )
        }
    }
}

@Composable
private fun HomeActionButton(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CapturePrimaryButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .size(112.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
                    shape = CircleShape,
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = "\u8fdb\u5165\u62cd\u6444",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CommunityPreviewRow(
    items: List<CaptureCommunityPreview>,
    openCommunity: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            CommunityPreviewTile(
                item = item,
                openCommunity = openCommunity,
                modifier = Modifier.weight(1f),
            )
        }
        repeat(2 - items.size) {
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.96f),
            )
        }
    }
}

@Composable
private fun CommunityPreviewTile(
    item: CaptureCommunityPreview,
    openCommunity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.96f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = openCommunity),
    ) {
        if (item.imageUrl != null) {
            AsyncImage(
                model = rememberHomePostRequest(
                    imageUrl = item.imageUrl,
                    authHeader = item.authHeader,
                ),
                contentDescription = "home-community-${item.id}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(item.placeholderColors)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.42f),
                        ),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberHomePostRequest(
    imageUrl: String,
    authHeader: String,
): ImageRequest {
    val context = androidx.compose.ui.platform.LocalContext.current
    return ImageRequest.Builder(context)
        .data(imageUrl)
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

private fun CommunityPostItem.toCapturePreview(authHeader: String): CaptureCommunityPreview {
    val fallbackTitle = caption.ifBlank {
        if (placeTag.isNotBlank()) placeTag else userNickname
    }
    return CaptureCommunityPreview(
        id = id,
        imageUrl = imageUrl,
        authHeader = authHeader,
        title = fallbackTitle,
        placeholderColors = listOf(
            Color(0xFFE8DDCD),
            Color(0xFFD7B486),
        ),
    )
}

private fun defaultCapturePreviews(): List<CaptureCommunityPreview> {
    val palettes = listOf(
        listOf(Color(0xFFF0E6DA), Color(0xFFD7B486)),
        listOf(Color(0xFFE6DED2), Color(0xFFBFA27F)),
        listOf(Color(0xFFEDE1D0), Color(0xFFC99A65)),
        listOf(Color(0xFFE1E2DB), Color(0xFFAB9470)),
        listOf(Color(0xFFE8D9C8), Color(0xFFCEA36F)),
        listOf(Color(0xFFEFE4D7), Color(0xFFB98A58)),
    )

    return palettes.mapIndexed { index, colors ->
        CaptureCommunityPreview(
            id = index,
            title = "\u7cbe\u9009\u63a8\u8350 ${index + 1}",
            placeholderColors = colors,
        )
    }
}
