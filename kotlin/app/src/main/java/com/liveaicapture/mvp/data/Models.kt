package com.liveaicapture.mvp.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

enum class GuideProvider(val raw: String, val label: String) {
    LOCAL("local", "本地引导"),
    CLOUD("cloud", "云端 AI");

    companion object {
        fun fromRaw(raw: String): GuideProvider {
            if (raw == "cloud_reserved") return CLOUD
            return entries.firstOrNull { it.raw == raw } ?: LOCAL
        }
    }
}

enum class CaptureMode(val raw: String, val label: String) {
    AUTO("auto", "自动"),
    PORTRAIT("portrait", "人像"),
    GENERAL("general", "通用"),
    FOOD("food", "美食");

    companion object {
        fun fromRaw(raw: String): CaptureMode = entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

enum class SceneType(val raw: String, val label: String) {
    PORTRAIT("portrait", "人像"),
    GENERAL("general", "通用"),
    LANDSCAPE("landscape", "风景"),
    FOOD("food", "美食"),
    NIGHT("night", "夜景");

    companion object {
        fun fromRaw(raw: String): SceneType {
            if (raw == "landscape") return GENERAL
            return entries.firstOrNull { it.raw == raw } ?: GENERAL
        }
    }
}

enum class RetouchPreset(val raw: String, val label: String) {
    BG_CLEANUP("bg_cleanup", "背景净化"),
    PORTRAIT_BEAUTY("portrait_beauty", "人像美化"),
    COLOR_GRADE("color_grade", "电影调色");

    companion object {
        fun fromRaw(raw: String): RetouchPreset {
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                "natural", "portrait", "portrait_beauty" -> PORTRAIT_BEAUTY
                "food", "night", "cinematic", "color_grade" -> COLOR_GRADE
                "bg_cleanup" -> BG_CLEANUP
                else -> BG_CLEANUP
            }
        }
    }
}

enum class RetouchMode(val raw: String, val label: String) {
    TEMPLATE("template", "模板"),
    CUSTOM("custom", "自定义");
}

data class AppSettings(
    val serverUrl: String = "http://10.0.2.2:8000",
    val intervalMs: Long = 1000L,
    val voiceEnabled: Boolean = true,
    val debugEnabled: Boolean = false,
    val guideProvider: GuideProvider = GuideProvider.CLOUD,
    val captureMode: CaptureMode = CaptureMode.AUTO,
)

data class OverlayState(
    val grid: String = "none",
    val targetPointNorm: Offset? = null,
    val bboxNorm: Rect? = null,
    val subjectCenterNorm: Offset? = null,
    val sourceAspectRatio: Float = 4f / 3f,
)

data class CameraUiState(
    val aiEnabled: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val overlay: OverlayState = OverlayState(),
    val tipText: String = "点击 AI 分析获取构图建议",
    val tipLevel: String = "info",
    val exposureSuggestion: Int? = null,
    val debugRaw: String = "",
    val statusText: String = "",
    val sessionFrameCount: Int = 0,
    val sessionTipCount: Int = 0,
    val photoCount: Int = 0,
    val lastPhotoUri: String? = null,
    val showPostCaptureChoice: Boolean = false,
    val detectedScene: SceneType = SceneType.GENERAL,
    val sceneConfidence: Float = 0f,
    val frameStable: Boolean = false,
    val stabilityScore: Float = 0f,
    val analyzingTips: Boolean = false,
    val moveHintText: String = "",
)

data class AuthUser(
    val id: Int,
    val email: String,
    val nickname: String,
)

data class AuthUiState(
    val checkingSession: Boolean = true,
    val authenticated: Boolean = false,
    val loading: Boolean = false,
    val user: AuthUser? = null,
    val errorMessage: String? = null,
)

data class StoredSession(
    val bearerToken: String = "",
    val userId: Int = 0,
    val userEmail: String = "",
    val userNickname: String = "",
    val expiresAtEpochSec: Long = 0L,
) {
    val isAvailable: Boolean
        get() = bearerToken.isNotBlank() && expiresAtEpochSec > 0L
}

data class RetouchUiState(
    val originalPhotoUri: String? = null,
    val preset: RetouchPreset = RetouchPreset.BG_CLEANUP,
    val mode: RetouchMode = RetouchMode.TEMPLATE,
    val customPrompt: String = "",
    val strength: Float = 0.35f,
    val applying: Boolean = false,
    val previewBase64: String? = null,
    val provider: String = "",
    val model: String = "",
    val requestId: String = "",
    val sceneHint: SceneType = SceneType.GENERAL,
    val errorMessage: String? = null,
)

data class FeedbackUiState(
    val visible: Boolean = false,
    val photoUri: String? = null,
    val isRetouched: Boolean = false,
    val scene: SceneType = SceneType.GENERAL,
    val tipText: String = "",
    val rating: Int = 5,
    val reviewText: String = "",
    val publishToCommunity: Boolean = false,
    val publishPlaceTag: String = "",
    val publishSceneType: String = "general",
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    val publishedPostId: Int? = null,
    val errorMessage: String? = null,
)

data class CommunityPostItem(
    val id: Int,
    val userId: Int,
    val userNickname: String,
    val feedbackId: Int,
    val imageUrl: String,
    val sceneType: String,
    val placeTag: String,
    val rating: Int,
    val reviewText: String,
    val createdAt: Long,
)

data class CommunityRecommendationItem(
    val post: CommunityPostItem,
    val score: Float,
    val reason: String,
)

data class CommunityUiState(
    val loadingFeed: Boolean = false,
    val loadingRecommendations: Boolean = false,
    val composing: Boolean = false,
    val feed: List<CommunityPostItem> = emptyList(),
    val recommendations: List<CommunityRecommendationItem> = emptyList(),
    val recommendationPlaceTag: String = "",
    val recommendationSceneType: String = "general",
    val referencePostId: Int? = null,
    val personImageUri: String? = null,
    val composedPreviewBase64: String? = null,
    val composeStrength: Float = 0.45f,
    val composeRequestId: String = "",
    val authHeader: String = "",
    val errorMessage: String? = null,
)

sealed interface AnalyzeEvent {
    data class Scene(
        val scene: SceneType,
        val mode: CaptureMode,
        val confidence: Float,
    ) : AnalyzeEvent

    data class Strategy(val grid: String, val targetPoint: Offset?) : AnalyzeEvent
    data class Target(val bbox: Rect?, val center: Offset?) : AnalyzeEvent
    data class Ui(val text: String, val level: String) : AnalyzeEvent
    data class Param(val exposureCompensation: Int) : AnalyzeEvent
    data object Done : AnalyzeEvent
}
