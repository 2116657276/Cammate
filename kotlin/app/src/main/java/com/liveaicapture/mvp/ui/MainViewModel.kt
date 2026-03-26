package com.liveaicapture.mvp.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liveaicapture.mvp.camera.FrameEncoder
import com.liveaicapture.mvp.data.AnalyzeEvent
import com.liveaicapture.mvp.data.AppSettings
import com.liveaicapture.mvp.data.AuthUiState
import com.liveaicapture.mvp.data.CameraUiState
import com.liveaicapture.mvp.data.CaptureMode
import com.liveaicapture.mvp.data.CommunityCommentItem
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.data.CommunityRecommendationItem
import com.liveaicapture.mvp.data.CommunityRelayParentItem
import com.liveaicapture.mvp.data.CommunityRemakeGuide
import com.liveaicapture.mvp.data.CommunityUiState
import com.liveaicapture.mvp.data.FeedbackUiState
import com.liveaicapture.mvp.data.GuideProvider
import com.liveaicapture.mvp.data.RetouchMode
import com.liveaicapture.mvp.data.RetouchPreset
import com.liveaicapture.mvp.data.RetouchUiState
import com.liveaicapture.mvp.data.SceneType
import com.liveaicapture.mvp.data.SessionRepository
import com.liveaicapture.mvp.data.SettingsRepository
import com.liveaicapture.mvp.log.AppLog
import com.liveaicapture.mvp.network.AnalyzeApiClient
import com.liveaicapture.mvp.network.AuthApiClient
import com.liveaicapture.mvp.network.CommunityApiClient
import com.liveaicapture.mvp.network.CommunityCommentDto
import com.liveaicapture.mvp.network.CommunityCreativeJobDto
import com.liveaicapture.mvp.network.CommunityPostDto
import com.liveaicapture.mvp.network.CommunityRemakeGuideDto
import com.liveaicapture.mvp.network.FeedbackApiClient
import com.liveaicapture.mvp.network.RetouchApiClient
import com.liveaicapture.mvp.network.SceneApiClient
import com.liveaicapture.mvp.network.SceneDetectResult
import com.liveaicapture.mvp.tts.TtsSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CamMate"
        private const val UI_STABILITY_PUBLISH_INTERVAL_MS = 320L
        private const val STABLE_SCENE_DETECT_INTERVAL_MS = 900L
        private const val UNSTABLE_SCENE_DETECT_INTERVAL_MS = 1_400L
        private const val MIN_UNSTABLE_SCENE_DETECT_INTERVAL_MS = 650L
        private const val SCENE_SWITCH_BOOST_WINDOW_MS = 2_200L
        private const val SCENE_SWITCH_BOOST_INTERVAL_MS = 500L
        private const val GENERAL_SUBJECT_MIN_CONFIDENCE = 0.54f
        private const val GENERAL_SUBJECT_MIN_AREA = 0.022f
        private const val GLOBAL_SUBJECT_MIN_AREA = 0.010f
    }

    private val settingsRepository = SettingsRepository(application)
    private val sessionRepository = SessionRepository(application)
    private val analyzeApiClient = AnalyzeApiClient()
    private val sceneApiClient = SceneApiClient()
    private val authApiClient = AuthApiClient()
    private val feedbackApiClient = FeedbackApiClient()
    private val retouchApiClient = RetouchApiClient()
    private val communityApiClient = CommunityApiClient()
    private var ttsSpeaker: TtsSpeaker? = null
    private val appLogger = AppLog.get(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _authUiState = MutableStateFlow(AuthUiState())
    val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

    private val _retouchUiState = MutableStateFlow(RetouchUiState())
    val retouchUiState: StateFlow<RetouchUiState> = _retouchUiState.asStateFlow()

    private val _feedbackUiState = MutableStateFlow(FeedbackUiState())
    val feedbackUiState: StateFlow<FeedbackUiState> = _feedbackUiState.asStateFlow()

    private val _communityUiState = MutableStateFlow(CommunityUiState())
    val communityUiState: StateFlow<CommunityUiState> = _communityUiState.asStateFlow()

    private val requestGate = Any()
    private var analyzeInFlight = false
    private var sceneDetectInFlight = false
    private var currentAnalyzeJob: Job? = null
    private var currentExposureCompensation = 0
    private var latestLensFacing = "back"

    private val frameLock = Any()
    private var latestFrameJpeg: ByteArray? = null
    private var latestFrameRotation = 0
    private var latestFrameCapturedAtMs = 0L
    private var latestFrameStable = false
    private var latestFrameStabilityScore = 0f
    private var lastSceneClassifyAt = 0L
    private var lastStableFrameAtMs = 0L
    private var previousLumaSignature: FloatArray? = null
    private var stableFrameStreak = 0
    private var lastUiStabilityUpdateAtMs = 0L
    private var lastPublishedAspectRatio = 4f / 3f
    private var lastPublishedStable = false
    private var lastPublishedStabilityScore = 0f
    private var lastSuccessfulTipText: String = ""
    private val recentSuccessfulTips = ArrayDeque<String>()
    private var nextAnalyzeAllowedAtMs = 0L
    private var sceneAutoSwitchLockedByManual = false
    private var sceneFastDetectUntilMs = 0L
    private var pendingSceneSwitch: SceneType? = null
    private var pendingSceneSwitchVotes = 0
    private var pendingSubjectJumpCenter: Offset? = null
    private var pendingSubjectJumpVotes = 0
    private var communityFeedOffset = 0
    private var composeJobPolling: Job? = null
    private var cocreateJobPolling: Job? = null

    private var bearerToken: String = ""

    init {
        observeSettings()
        bootstrapSession()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        statusText = statusByProvider(settings.guideProvider, it.aiEnabled),
                    )
                }
            }
        }
    }

    private fun bootstrapSession() {
        viewModelScope.launch {
            _authUiState.update { it.copy(checkingSession = true, errorMessage = null) }

            val session = sessionRepository.readSession()
            val nowSec = System.currentTimeMillis() / 1000L
            if (!session.isAvailable || session.expiresAtEpochSec <= nowSec) {
                sessionRepository.clearSession()
                bearerToken = ""
                syncCommunityAuthHeader()
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = false,
                    user = null,
                    errorMessage = null,
                )
                return@launch
            }

            bearerToken = session.bearerToken
            syncCommunityAuthHeader()
            try {
                val user = authApiClient.me(_uiState.value.settings.serverUrl, bearerToken)
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = true,
                    user = user,
                    errorMessage = null,
                )
            } catch (e: Exception) {
                logClientError(
                    scope = "auth.bootstrapSession.me",
                    throwable = e,
                    userHint = "会话校验失败，已清理本地登录态",
                )
                sessionRepository.clearSession()
                bearerToken = ""
                syncCommunityAuthHeader()
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = false,
                    user = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun clearAuthError() {
        _authUiState.update { it.copy(errorMessage = null) }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            logClientInfo("auth.login", "start email=$email")
            _authUiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val result = authApiClient.login(
                    serverUrl = _uiState.value.settings.serverUrl,
                    email = email,
                    password = password,
                )
                bearerToken = result.bearerToken
                syncCommunityAuthHeader()
                sessionRepository.saveSession(result.bearerToken, result.expiresInSec, result.user)
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = true,
                    loading = false,
                    user = result.user,
                    errorMessage = null,
                )
                logClientInfo("auth.login", "success user=${result.user.id}")
            } catch (e: Exception) {
                logClientError(
                    scope = "auth.login",
                    throwable = e,
                    userHint = "登录失败",
                )
                _authUiState.update {
                    it.copy(
                        loading = false,
                        authenticated = false,
                        user = null,
                        errorMessage = userFriendlyAuthError(e, action = "login"),
                    )
                }
            }
        }
    }

    fun register(email: String, password: String, nickname: String) {
        viewModelScope.launch {
            logClientInfo("auth.register", "start email=$email")
            _authUiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val result = authApiClient.register(
                    serverUrl = _uiState.value.settings.serverUrl,
                    email = email,
                    password = password,
                    nickname = nickname,
                )
                bearerToken = result.bearerToken
                syncCommunityAuthHeader()
                sessionRepository.saveSession(result.bearerToken, result.expiresInSec, result.user)
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = true,
                    loading = false,
                    user = result.user,
                    errorMessage = null,
                )
                logClientInfo("auth.register", "success user=${result.user.id}")
            } catch (e: Exception) {
                logClientError(
                    scope = "auth.register",
                    throwable = e,
                    userHint = "注册失败",
                )
                _authUiState.update {
                    it.copy(
                        loading = false,
                        authenticated = false,
                        user = null,
                        errorMessage = userFriendlyAuthError(e, action = "register"),
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            logClientInfo("auth.logout", "start")
            val token = bearerToken
            val serverUrl = _uiState.value.settings.serverUrl

            // 本地会话先立即清理，确保“退出登录”按钮可即时响应。
            sessionRepository.clearSession()
            bearerToken = ""
            syncCommunityAuthHeader()
            _authUiState.value = AuthUiState(
                checkingSession = false,
                authenticated = false,
                loading = false,
                user = null,
                errorMessage = null,
            )
            resetFlowAfterLogout()
            logClientInfo("auth.logout", "local_session_cleared")

            // 服务端会话回收采用异步 best-effort，避免弱网下阻塞 UI。
            if (token.isNotBlank()) {
                viewModelScope.launch {
                    val remoteOk = withTimeoutOrNull(3500L) {
                        try {
                            authApiClient.logout(serverUrl, token)
                            true
                        } catch (e: Exception) {
                            logClientError(
                                scope = "auth.logout.remote",
                                throwable = e,
                                userHint = "登出接口调用失败",
                            )
                            false
                        }
                    } ?: false
                    logClientInfo("auth.logout", "remote_revoke=$remoteOk")
                }
            }
        }
    }

    private fun resetFlowAfterLogout() {
        composeJobPolling?.cancel()
        composeJobPolling = null
        cocreateJobPolling?.cancel()
        cocreateJobPolling = null
        _retouchUiState.value = RetouchUiState()
        _feedbackUiState.value = FeedbackUiState()
        _communityUiState.value = CommunityUiState()
        communityFeedOffset = 0
        lastSuccessfulTipText = ""
        recentSuccessfulTips.clear()
        sceneAutoSwitchLockedByManual = false
        pendingSceneSwitch = null
        pendingSceneSwitchVotes = 0
        pendingSubjectJumpCenter = null
        pendingSubjectJumpVotes = 0
        _uiState.update {
            it.copy(
                aiEnabled = true,
                tipText = "点击 AI分析 获取构图建议",
                statusText = "请登录后开始拍摄",
                showPostCaptureChoice = false,
                overlay = it.overlay.copy(
                    grid = "none",
                    targetPointNorm = null,
                    bboxNorm = null,
                    subjectCenterNorm = null,
                ),
                moveHintText = "",
            )
        }
    }

    fun setAiEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                aiEnabled = enabled,
                statusText = statusByProvider(it.settings.guideProvider, enabled),
            )
        }
    }

    fun onCameraSessionEntered() {
        sceneAutoSwitchLockedByManual = false
        lastSceneClassifyAt = 0L
        sceneFastDetectUntilMs = 0L
        pendingSceneSwitch = null
        pendingSceneSwitchVotes = 0
        pendingSubjectJumpCenter = null
        pendingSubjectJumpVotes = 0
        _uiState.update { it.copy(showPostCaptureChoice = false) }
    }

    fun onCameraSessionExited() {
        // Session-scoped auto-switch flags are reset on next enter.
    }

    fun onFrame(imageProxy: ImageProxy, lensFacing: String = "back") {
        val snapshot = _uiState.value
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val now = System.currentTimeMillis()
        latestLensFacing = if (lensFacing == "front") "front" else "back"

        val frameAspectRatio = computeRotatedAspectRatio(
            width = imageProxy.width,
            height = imageProxy.height,
            rotationDegrees = rotationDegrees,
        )

        val stabilityScore = estimateFrameStability(imageProxy)
        val isStable = updateStableStreak(stabilityScore)
        val shouldPublishStability = (
            now - lastUiStabilityUpdateAtMs >= UI_STABILITY_PUBLISH_INTERVAL_MS ||
                abs(frameAspectRatio - lastPublishedAspectRatio) >= 0.01f ||
                isStable != lastPublishedStable ||
                abs(stabilityScore - lastPublishedStabilityScore) >= 0.08f
            )
        if (shouldPublishStability) {
            _uiState.update {
                it.copy(
                    overlay = it.overlay.copy(sourceAspectRatio = frameAspectRatio),
                    frameStable = isStable,
                    stabilityScore = stabilityScore,
                )
            }
            lastUiStabilityUpdateAtMs = now
            lastPublishedAspectRatio = frameAspectRatio
            lastPublishedStable = isStable
            lastPublishedStabilityScore = stabilityScore
        }

        if (!snapshot.aiEnabled) {
            imageProxy.close()
            return
        }

        val unstableDetectInterval = snapshot.settings.intervalMs
            .coerceIn(MIN_UNSTABLE_SCENE_DETECT_INTERVAL_MS, UNSTABLE_SCENE_DETECT_INTERVAL_MS)
        val baseClassifyInterval = if (isStable) {
            STABLE_SCENE_DETECT_INTERVAL_MS
        } else {
            unstableDetectInterval
        }
        val classifyInterval = if (now < sceneFastDetectUntilMs) {
            min(baseClassifyInterval, SCENE_SWITCH_BOOST_INTERVAL_MS)
        } else {
            baseClassifyInterval
        }
        val shouldClassify = now - lastSceneClassifyAt >= classifyInterval
        val canClassifyNow = shouldClassify &&
            !snapshot.analyzingTips &&
            _authUiState.value.authenticated &&
            bearerToken.isNotBlank() &&
            !isSceneDetectBusy()
        val shouldRefreshAnalyzeFrame = isStable && (now - lastStableFrameAtMs >= 1200L)
        val shouldEncodeFrame = canClassifyNow || shouldRefreshAnalyzeFrame || latestFrameJpeg == null
        if (!shouldEncodeFrame) {
            imageProxy.close()
            return
        }

        val jpegBytes = try {
            FrameEncoder.imageProxyToJpegBytes(imageProxy)
        } catch (e: Exception) {
            logClientError(
                scope = "camera.frame.encode",
                throwable = e,
                userHint = "帧编码失败",
            )
            _uiState.update { it.copy(statusText = "帧编码失败") }
            imageProxy.close()
            return
        }
        imageProxy.close()

        if (shouldRefreshAnalyzeFrame || now - latestFrameCapturedAtMs > 2000L) {
            synchronized(frameLock) {
                latestFrameJpeg = jpegBytes
                latestFrameRotation = rotationDegrees
                latestFrameCapturedAtMs = now
                latestFrameStable = isStable
                latestFrameStabilityScore = stabilityScore
            }
            if (shouldRefreshAnalyzeFrame) {
                lastStableFrameAtMs = now
            }
        }

        if (!canClassifyNow) return
        if (!tryAcquireSceneDetect()) return
        lastSceneClassifyAt = now

        val serverUrl = snapshot.settings.serverUrl
        val mode = snapshot.settings.captureMode
        val sceneHint = _uiState.value.detectedScene.raw
        val requestBytes = jpegBytes.copyOf()
        viewModelScope.launch {
            try {
                val classified = sceneApiClient.detect(
                    serverUrl = serverUrl,
                    bearerToken = bearerToken,
                    imageBase64 = Base64.encodeToString(requestBytes, Base64.NO_WRAP),
                    rotationDegrees = rotationDegrees,
                    lensFacing = latestLensFacing,
                    captureMode = mode.raw,
                    sceneHint = sceneHint,
                )
                applySceneDetectResult(classified)
                _uiState.update { it.copy(sessionFrameCount = it.sessionFrameCount + 1) }
            } catch (e: Exception) {
                logClientError(
                    scope = "camera.frame.sceneDetect",
                    throwable = e,
                    userHint = "场景识别失败",
                )
            } finally {
                releaseSceneDetect()
            }
        }
    }

    fun requestAiAnalyze() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _uiState.update { it.copy(statusText = "请先登录") }
            return
        }

        val now = System.currentTimeMillis()
        if (now < nextAnalyzeAllowedAtMs) {
            val remain = ((nextAnalyzeAllowedAtMs - now) / 1000L).coerceAtLeast(1L)
            _uiState.update { it.copy(statusText = "云端冷却中，请 ${remain}s 后重试") }
            return
        }

        val frameSnapshot = synchronized(frameLock) {
            val bytes = latestFrameJpeg?.copyOf() ?: return@synchronized null
            AnalyzeFrameSnapshot(
                bytes = bytes,
                rotation = latestFrameRotation,
                capturedAtMs = latestFrameCapturedAtMs,
                stable = latestFrameStable,
                stabilityScore = latestFrameStabilityScore,
            )
        }

        if (frameSnapshot == null) {
            _uiState.update { it.copy(statusText = "请先稳定取景") }
            return
        }
        if (!frameSnapshot.stable || System.currentTimeMillis() - frameSnapshot.capturedAtMs > 2500L) {
            _uiState.update { it.copy(statusText = "请先稳住画面后再点 AI分析") }
            return
        }

        if (!tryAcquireAnalyzeRequest()) {
            _uiState.update { it.copy(statusText = "AI分析进行中") }
            return
        }

        _uiState.update {
            it.copy(
                analyzingTips = true,
                tipText = "AI 正在思考，请保持画面稳定",
                tipLevel = "info",
                statusText = "AI分析中...",
                overlay = it.overlay.copy(
                    grid = "none",
                    targetPointNorm = null,
                ),
                moveHintText = "",
            )
        }
        logClientInfo("analyze.request", "started")

        val fallbackScene = _uiState.value.detectedScene
        val previousTipText = lastSuccessfulTipText
        val recentTipTexts = recentSuccessfulTips.toList()
        currentAnalyzeJob?.cancel()
        currentAnalyzeJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            var finalStatus = statusByProvider(_uiState.value.settings.guideProvider, _uiState.value.aiEnabled)
            try {
                val activeSettings = _uiState.value.settings
                _uiState.update { it.copy(statusText = "云端思考中...") }
                finalStatus = runCloudGuidanceStrict(
                    settings = activeSettings,
                    jpegBytes = frameSnapshot.bytes,
                    rotationDegrees = frameSnapshot.rotation,
                    stable = frameSnapshot.stable,
                    stabilityScore = frameSnapshot.stabilityScore,
                    fallbackScene = fallbackScene,
                    previousTipText = previousTipText,
                    recentTipTexts = recentTipTexts,
                )
            } catch (e: Exception) {
                logClientError(
                    scope = "analyze.request",
                    throwable = e,
                    userHint = "分析失败",
                )
                nextAnalyzeAllowedAtMs = max(nextAnalyzeAllowedAtMs, now + 2500L)
                _uiState.update {
                    it.copy(
                        tipText = "AI分析失败，请重试",
                        tipLevel = "warn",
                    )
                }
                finalStatus = "分析失败，请重试"
            } finally {
                releaseAnalyzeRequest()
                _uiState.update {
                    it.copy(
                        analyzingTips = false,
                        statusText = finalStatus,
                    )
                }
                logClientInfo("analyze.request", "finished status=$finalStatus")
            }
        }
    }

    private suspend fun runCloudGuidanceStrict(
        settings: com.liveaicapture.mvp.data.AppSettings,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        stable: Boolean,
        stabilityScore: Float,
        fallbackScene: SceneType,
        previousTipText: String,
        recentTipTexts: List<String>,
    ): String {
        val imageBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val overlaySnapshot = _uiState.value.overlay
        try {
            var receivedUiEvent = false
            analyzeApiClient.analyze(
                serverUrl = settings.serverUrl,
                bearerToken = bearerToken,
                imageBase64 = imageBase64,
                rotationDegrees = rotationDegrees,
                lensFacing = latestLensFacing,
                exposureCompensation = currentExposureCompensation,
                captureMode = settings.captureMode.raw,
                sceneHint = fallbackScene.raw,
                previousTipText = previousTipText,
                recentTipTexts = recentTipTexts,
                subjectCenter = overlaySnapshot.subjectCenterNorm,
                subjectBbox = overlaySnapshot.bboxNorm,
                frameStable = stable,
                stabilityScore = stabilityScore,
                onRawLine = { line ->
                    if (settings.debugEnabled) {
                        _uiState.update { state ->
                            val merged = listOf(state.debugRaw, line).filter { it.isNotBlank() }
                                .takeLast(8)
                                .joinToString("\n")
                            state.copy(debugRaw = merged.takeLast(4000))
                        }
                    }
                },
                onEvent = { event ->
                    if (event is AnalyzeEvent.Ui) {
                        receivedUiEvent = true
                    }
                    applyEvent(event)
                },
            )
            if (!receivedUiEvent) {
                logClientError(
                    scope = "analyze.cloud.no_ui",
                    throwable = null,
                    userHint = "云端未返回文本建议",
                )
                _uiState.update {
                    it.copy(
                        tipText = "云端未返回新建议，请重试",
                        tipLevel = "warn",
                    )
                }
                return "云端未返回新建议"
            }
            return "云端建议已更新"
        } catch (e: Exception) {
            logClientError(
                scope = "analyze.cloud.exception",
                throwable = e,
                userHint = "云端连接失败",
            )
            _uiState.update {
                it.copy(
                    tipText = "云端连接失败，请检查网络后重试",
                    tipLevel = "warn",
                )
            }
            return "云端连接失败"
        }
    }

    private fun applyEvent(event: AnalyzeEvent) {
        when (event) {
            is AnalyzeEvent.Scene -> {
                _uiState.update {
                    val incomingConfidence = event.confidence.coerceIn(0f, 1f)
                    val currentScene = it.detectedScene
                    val currentConfidence = it.sceneConfidence.coerceIn(0f, 1f)
                    val sceneChanged = event.scene != currentScene
                    val autoModeEnabled = it.settings.captureMode == CaptureMode.AUTO
                    val confidenceGate = max(0.62f, currentConfidence + 0.08f)
                    val confidenceAllowsSwitch = incomingConfidence >= confidenceGate
                    val strongSwitch = incomingConfidence >= 0.78f
                    val canAutoSwitchNow = (
                        autoModeEnabled &&
                            !sceneAutoSwitchLockedByManual
                        )
                    val shouldSwitchScene = (
                        event.scene != currentScene &&
                            canAutoSwitchNow &&
                            (
                                strongSwitch ||
                                    confidenceAllowsSwitch ||
                                    currentConfidence <= 0.35f
                                )
                        )

                    if (sceneChanged && (strongSwitch || confidenceAllowsSwitch)) {
                        sceneFastDetectUntilMs = max(
                            sceneFastDetectUntilMs,
                            System.currentTimeMillis() + SCENE_SWITCH_BOOST_WINDOW_MS,
                        )
                    }

                    if (event.scene == currentScene) {
                        it.copy(
                            sceneConfidence = incomingConfidence,
                        )
                    } else if (shouldSwitchScene) {
                        it.copy(
                            detectedScene = event.scene,
                            sceneConfidence = incomingConfidence,
                        )
                    } else {
                        it.copy(
                            sceneConfidence = max(currentConfidence * 0.90f, incomingConfidence * 0.72f),
                        )
                    }
                }
            }

            is AnalyzeEvent.Strategy -> {
                _uiState.update {
                    val blended = blendOffset(
                        previous = it.overlay.targetPointNorm,
                        incoming = event.targetPoint,
                        alpha = 0.32f,
                    )
                    it.copy(
                        overlay = it.overlay.copy(
                            grid = event.grid,
                            targetPointNorm = blended,
                        ),
                        moveHintText = buildMoveHintText(
                            bbox = it.overlay.bboxNorm,
                            targetPoint = blended,
                            subjectCenter = it.overlay.subjectCenterNorm,
                        ),
                    )
                }
            }

            is AnalyzeEvent.Target -> {
                _uiState.update {
                    val blendedBox = blendRect(
                        previous = it.overlay.bboxNorm,
                        incoming = event.bbox,
                        alpha = 0.35f,
                    )
                    val blendedCenter = blendOffset(
                        previous = it.overlay.subjectCenterNorm,
                        incoming = event.center,
                        alpha = 0.28f,
                    )
                    it.copy(
                        overlay = it.overlay.copy(
                            bboxNorm = blendedBox,
                            subjectCenterNorm = blendedCenter ?: it.overlay.subjectCenterNorm,
                        ),
                        moveHintText = buildMoveHintText(
                            bbox = blendedBox,
                            targetPoint = it.overlay.targetPointNorm,
                            subjectCenter = blendedCenter ?: it.overlay.subjectCenterNorm,
                        ),
                    )
                }
            }

            is AnalyzeEvent.Ui -> {
                val normalizedTip = normalizeUiTipText(event.text)
                _uiState.update {
                    it.copy(
                        tipText = normalizedTip,
                        tipLevel = event.level,
                        sessionTipCount = it.sessionTipCount + 1,
                    )
                }
                if (event.level == "info") {
                    val tip = normalizedTip.trim()
                    lastSuccessfulTipText = tip
                    if (tip.isNotEmpty()) {
                        recentSuccessfulTips.remove(tip)
                        recentSuccessfulTips.addLast(tip)
                        while (recentSuccessfulTips.size > 4) {
                            recentSuccessfulTips.removeFirst()
                        }
                    }
                } else {
                    val lowered = normalizedTip.lowercase()
                    if (lowered.contains("限流") || lowered.contains("冷却")) {
                        nextAnalyzeAllowedAtMs = max(nextAnalyzeAllowedAtMs, System.currentTimeMillis() + 10_000L)
                    }
                }
                if (_uiState.value.settings.voiceEnabled) {
                    getOrCreateTtsSpeaker().speak(normalizedTip, enabled = true)
                }
            }

            is AnalyzeEvent.Param -> {
                currentExposureCompensation = event.exposureCompensation
                _uiState.update {
                    it.copy(exposureSuggestion = event.exposureCompensation)
                }
            }

            AnalyzeEvent.Done -> {
                _uiState.update {
                    it.copy(
                        statusText = statusByProvider(
                            provider = it.settings.guideProvider,
                            aiEnabled = it.aiEnabled,
                        ),
                    )
                }
            }
        }
    }

    private fun tryAcquireAnalyzeRequest(): Boolean {
        synchronized(requestGate) {
            if (analyzeInFlight) return false
            analyzeInFlight = true
            return true
        }
    }

    private fun tryAcquireSceneDetect(): Boolean {
        synchronized(requestGate) {
            if (sceneDetectInFlight) return false
            sceneDetectInFlight = true
            return true
        }
    }

    private fun applySceneDetectResult(classified: SceneDetectResult) {
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            val incomingScene = classified.scene
            val incomingConfidence = classified.confidence.coerceIn(0f, 1f)
            val decision = decideSceneForPreview(
                state = state,
                incomingScene = incomingScene,
                incomingConfidence = incomingConfidence,
            )
            if (decision.switched) {
                sceneFastDetectUntilMs = max(
                    sceneFastDetectUntilMs,
                    now + SCENE_SWITCH_BOOST_WINDOW_MS,
                )
            }

            val sceneForSubject = if (decision.acceptIncomingOverlay) incomingScene else decision.scene
            val confidenceForSubject = if (decision.acceptIncomingOverlay) incomingConfidence else decision.confidence
            val suppressSubject = shouldSuppressSubjectOverlay(
                scene = sceneForSubject,
                sceneConfidence = confidenceForSubject,
                bbox = classified.bbox,
            )
            var incomingBox = if (decision.acceptIncomingOverlay && !suppressSubject) classified.bbox else null
            var incomingCenter = if (decision.acceptIncomingOverlay && !suppressSubject) classified.center else null

            val previousCenter = state.overlay.subjectCenterNorm
            if (state.frameStable && previousCenter != null && incomingCenter != null) {
                val shift = offsetDistance(previousCenter, incomingCenter)
                if (shift <= 0.018f) {
                    // Ignore tiny jitter while frame is stable.
                    incomingCenter = previousCenter
                } else if (shift >= 0.12f) {
                    val confidenceGain = incomingConfidence - state.sceneConfidence.coerceIn(0f, 1f)
                    val sameJump = pendingSubjectJumpCenter?.let { offsetDistance(it, incomingCenter!!) <= 0.04f } ?: false
                    pendingSubjectJumpVotes = if (sameJump) pendingSubjectJumpVotes + 1 else 1
                    pendingSubjectJumpCenter = incomingCenter
                    val acceptJump = confidenceGain >= 0.16f || pendingSubjectJumpVotes >= 2
                    if (!acceptJump) {
                        // A sudden jump needs a second consistent frame to avoid random drifting.
                        incomingCenter = previousCenter
                        incomingBox = state.overlay.bboxNorm
                    } else {
                        pendingSubjectJumpCenter = null
                        pendingSubjectJumpVotes = 0
                    }
                } else {
                    pendingSubjectJumpCenter = null
                    pendingSubjectJumpVotes = 0
                }
            } else if (!state.frameStable) {
                pendingSubjectJumpCenter = null
                pendingSubjectJumpVotes = 0
            }

            val blendedBox = blendRect(
                previous = state.overlay.bboxNorm,
                incoming = incomingBox,
                alpha = 0.28f,
            )
            val blendedCenter = blendOffset(
                previous = state.overlay.subjectCenterNorm,
                incoming = incomingCenter,
                alpha = 0.20f,
            )
            val nextBox = when {
                incomingBox == null -> null
                else -> blendedBox ?: state.overlay.bboxNorm
            }
            val nextCenter = when {
                incomingCenter == null -> null
                else -> blendedCenter ?: state.overlay.subjectCenterNorm
            }
            val nextOverlay = state.overlay.copy(
                bboxNorm = nextBox,
                subjectCenterNorm = nextCenter,
            )
            state.copy(
                detectedScene = decision.scene,
                sceneConfidence = decision.confidence,
                overlay = nextOverlay,
                moveHintText = buildMoveHintText(
                    bbox = nextOverlay.bboxNorm,
                    targetPoint = nextOverlay.targetPointNorm,
                    subjectCenter = nextOverlay.subjectCenterNorm,
                ),
            )
        }
    }

    private fun decideSceneForPreview(
        state: CameraUiState,
        incomingScene: SceneType,
        incomingConfidence: Float,
    ): SceneDecision {
        val currentScene = state.detectedScene
        val currentConfidence = state.sceneConfidence.coerceIn(0f, 1f)
        val lockedScene = lockedSceneByCaptureMode(state.settings.captureMode)
        if (lockedScene != null) {
            pendingSceneSwitch = null
            pendingSceneSwitchVotes = 0
            return SceneDecision(
                scene = lockedScene,
                confidence = incomingConfidence,
                acceptIncomingOverlay = true,
                switched = lockedScene != currentScene,
            )
        }

        if (incomingScene == currentScene) {
            pendingSceneSwitch = null
            pendingSceneSwitchVotes = 0
            val mergedConfidence = if (currentConfidence <= 0f) {
                incomingConfidence
            } else {
                (currentConfidence * 0.32f + incomingConfidence * 0.68f).coerceIn(0f, 1f)
            }
            return SceneDecision(
                scene = currentScene,
                confidence = mergedConfidence,
                acceptIncomingOverlay = true,
                switched = false,
            )
        }

        val nearCurrent = incomingConfidence + 0.04f >= currentConfidence
        pendingSceneSwitchVotes = if (pendingSceneSwitch == incomingScene && nearCurrent) {
            pendingSceneSwitchVotes + 1
        } else {
            1
        }
        pendingSceneSwitch = incomingScene

        val strongSwitch = incomingConfidence >= 0.72f
        val weakCurrent = currentConfidence <= 0.46f && incomingConfidence >= 0.50f
        val streakSwitch = pendingSceneSwitchVotes >= 2 && incomingConfidence >= 0.52f && nearCurrent
        val shouldSwitch = strongSwitch || weakCurrent || streakSwitch

        if (shouldSwitch) {
            pendingSceneSwitch = null
            pendingSceneSwitchVotes = 0
            return SceneDecision(
                scene = incomingScene,
                confidence = incomingConfidence,
                acceptIncomingOverlay = true,
                switched = true,
            )
        }

        val softenedConfidence = max(currentConfidence * 0.95f, incomingConfidence * 0.88f).coerceIn(0f, 1f)
        return SceneDecision(
            scene = currentScene,
            confidence = softenedConfidence,
            acceptIncomingOverlay = false,
            switched = false,
        )
    }

    private fun lockedSceneByCaptureMode(mode: CaptureMode): SceneType? {
        return when (mode) {
            CaptureMode.AUTO -> null
            CaptureMode.PORTRAIT -> SceneType.PORTRAIT
            CaptureMode.GENERAL -> SceneType.GENERAL
            CaptureMode.FOOD -> SceneType.FOOD
        }
    }

    private fun isSceneDetectBusy(): Boolean {
        synchronized(requestGate) {
            return sceneDetectInFlight
        }
    }

    private fun releaseAnalyzeRequest() {
        synchronized(requestGate) {
            analyzeInFlight = false
        }
    }

    private fun releaseSceneDetect() {
        synchronized(requestGate) {
            sceneDetectInFlight = false
        }
    }

    fun onPhotoCaptured(savedUri: String?) {
        logClientInfo("photo.capture", "savedUri=${savedUri ?: "null"}")
        _uiState.update {
            it.copy(
                photoCount = it.photoCount + 1,
                lastPhotoUri = savedUri,
                showPostCaptureChoice = !savedUri.isNullOrBlank(),
                statusText = "照片已保存",
            )
        }
        _retouchUiState.value = RetouchUiState(
            originalPhotoUri = savedUri,
            sceneHint = _uiState.value.detectedScene,
        )
        _feedbackUiState.value = FeedbackUiState()
    }

    fun dismissPostCaptureChoice() {
        _uiState.update { it.copy(showPostCaptureChoice = false) }
    }

    fun updateRetouchPreset(preset: RetouchPreset) {
        _retouchUiState.update { it.copy(preset = preset, requestId = "", errorMessage = null) }
    }

    fun updateRetouchMode(mode: RetouchMode) {
        _retouchUiState.update { it.copy(mode = mode, requestId = "", errorMessage = null) }
    }

    fun updateRetouchCustomPrompt(prompt: String) {
        _retouchUiState.update { it.copy(customPrompt = prompt.take(240), requestId = "", errorMessage = null) }
    }

    fun updateRetouchStrength(strength: Float) {
        _retouchUiState.update { it.copy(strength = strength.coerceIn(0f, 1f), requestId = "", errorMessage = null) }
    }

    fun applyRetouch() {
        val current = _retouchUiState.value
        val sourceUri = current.originalPhotoUri
        if (sourceUri.isNullOrBlank()) {
            _retouchUiState.update { it.copy(errorMessage = "未找到原图，请重新拍摄") }
            return
        }
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _retouchUiState.update { it.copy(errorMessage = "请先登录后再使用 AI 修图") }
            return
        }
        if (current.mode == RetouchMode.CUSTOM && current.customPrompt.trim().isBlank()) {
            _retouchUiState.update { it.copy(errorMessage = "请输入自定义修图提示词") }
            return
        }
        if (current.applying) {
            return
        }

        viewModelScope.launch {
            logClientInfo("retouch.apply", "start mode=${current.mode.raw} preset=${current.preset.raw}")
            _retouchUiState.update { it.copy(applying = true, requestId = "", errorMessage = null) }
            try {
                val imageBase64 = encodePhotoUriToBase64(sourceUri)
                val customPrompt = if (current.mode == RetouchMode.CUSTOM) current.customPrompt.trim() else null
                val result = retouchApiClient.retouch(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    imageBase64 = imageBase64,
                    preset = current.preset.raw,
                    strength = current.strength,
                    sceneHint = current.sceneHint.raw,
                    customPrompt = customPrompt,
                )
                if (result.imageBase64.isBlank()) {
                    throw IllegalStateException("empty retouched image")
                }
                _retouchUiState.update {
                    it.copy(
                        applying = false,
                        previewBase64 = result.imageBase64,
                        provider = result.provider,
                        model = result.model,
                        requestId = result.requestId,
                        errorMessage = null,
                    )
                }
                logClientInfo("retouch.apply", "success model=${result.model} req=${result.requestId}")
            } catch (e: Exception) {
                logClientError(
                    scope = "retouch.apply",
                    throwable = e,
                    userHint = "AI 修图失败",
                )
                _retouchUiState.update {
                    it.copy(
                        applying = false,
                        requestId = extractRequestId(e),
                        errorMessage = userFriendlyRetouchError(e),
                    )
                }
            }
        }
    }

    fun continueWithOriginalPhoto() {
        val sourceUri = _retouchUiState.value.originalPhotoUri ?: return
        _uiState.update { it.copy(showPostCaptureChoice = false) }
        _feedbackUiState.value = FeedbackUiState(
            visible = true,
            photoUri = sourceUri,
            isRetouched = false,
            scene = _uiState.value.detectedScene,
            tipText = _uiState.value.tipText,
            publishSceneType = _uiState.value.detectedScene.raw,
        )
    }

    fun restartRetouchFromOriginal() {
        val current = _retouchUiState.value
        _retouchUiState.value = current.copy(
            mode = RetouchMode.TEMPLATE,
            preset = RetouchPreset.BG_CLEANUP,
            customPrompt = "",
            strength = 0.35f,
            applying = false,
            previewBase64 = null,
            provider = "",
            model = "",
            requestId = "",
            errorMessage = null,
        )
    }

    fun continueWithRetouchedPhoto() {
        val retouched = _retouchUiState.value.previewBase64
        _uiState.update { it.copy(showPostCaptureChoice = false) }
        if (retouched.isNullOrBlank()) {
            continueWithOriginalPhoto()
            return
        }

        viewModelScope.launch {
            val savedUri = try {
                saveBase64ToGallery(retouched, "CamMate_Retouch")
            } catch (e: Exception) {
                logClientError(
                    scope = "retouch.saveToGallery",
                    throwable = e,
                    userHint = "修图结果保存失败，改用原图继续",
                )
                null
            }
            val finalUri = savedUri ?: _retouchUiState.value.originalPhotoUri
            _feedbackUiState.value = FeedbackUiState(
                visible = true,
                photoUri = finalUri,
                isRetouched = true,
                scene = _uiState.value.detectedScene,
                tipText = _uiState.value.tipText,
                publishSceneType = _uiState.value.detectedScene.raw,
            )
        }
    }

    fun updateFeedbackRating(value: Int) {
        _feedbackUiState.update {
            it.copy(rating = value.coerceIn(1, 5), errorMessage = null)
        }
    }

    fun updateFeedbackReviewText(value: String) {
        _feedbackUiState.update {
            it.copy(reviewText = value.take(280), errorMessage = null)
        }
    }

    fun updateFeedbackPublishEnabled(enabled: Boolean) {
        _feedbackUiState.update {
            it.copy(publishToCommunity = enabled, errorMessage = null)
        }
    }

    fun updateFeedbackPublishPlaceTag(value: String) {
        _feedbackUiState.update {
            it.copy(publishPlaceTag = value.take(48), errorMessage = null)
        }
    }

    fun updateFeedbackPublishSceneType(value: String) {
        val normalized = value.trim().lowercase().ifBlank { "general" }
        _feedbackUiState.update {
            it.copy(publishSceneType = normalized, errorMessage = null)
        }
    }

    fun submitFeedback() {
        val current = _feedbackUiState.value
        if (!current.visible || current.submitting) return
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _feedbackUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        if (current.publishToCommunity && current.publishPlaceTag.trim().isBlank()) {
            _feedbackUiState.update { it.copy(errorMessage = "发布社区前请填写地点标签") }
            return
        }
        if (current.publishToCommunity && current.photoUri.isNullOrBlank()) {
            _feedbackUiState.update { it.copy(errorMessage = "未找到可发布图片，请重新拍摄") }
            return
        }

        viewModelScope.launch {
            logClientInfo("feedback.submit", "start rating=${current.rating} retouch=${current.isRetouched}")
            _feedbackUiState.update { it.copy(submitting = true, errorMessage = null) }
            try {
                val feedbackId = feedbackApiClient.submit(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    rating = current.rating,
                    scene = current.scene.raw,
                    tipText = current.tipText,
                    photoUri = current.photoUri,
                    isRetouch = current.isRetouched,
                    reviewText = current.reviewText.trim(),
                    sessionMeta = buildJsonObject {
                        val retouchState = _retouchUiState.value
                        val presetValue = if (retouchState.mode == RetouchMode.CUSTOM) "custom" else retouchState.preset.raw
                        put("frame_count", JsonPrimitive(_uiState.value.sessionFrameCount))
                        put("tip_count", JsonPrimitive(_uiState.value.sessionTipCount))
                        put("photo_count", JsonPrimitive(_uiState.value.photoCount))
                        put("capture_mode", JsonPrimitive(_uiState.value.settings.captureMode.raw))
                        put("guide_provider", JsonPrimitive(_uiState.value.settings.guideProvider.raw))
                        put("retouch_preset", JsonPrimitive(presetValue))
                        put("retouch_mode", JsonPrimitive(retouchState.mode.raw))
                    },
                )

                if (feedbackId <= 0) throw IllegalStateException("反馈提交失败")
                var publishedPostId: Int? = null
                if (current.publishToCommunity) {
                    val photoUri = current.photoUri ?: throw IllegalStateException("未找到可发布图片")
                    val imageBase64 = encodePhotoUriToBase64(photoUri)
                    val post = communityApiClient.publishPost(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        feedbackId = feedbackId,
                        imageBase64 = imageBase64,
                        placeTag = current.publishPlaceTag.trim(),
                        sceneType = current.publishSceneType,
                    )
                    publishedPostId = post.id.takeIf { it > 0 }
                    refreshCommunityFeed()
                }
                _feedbackUiState.update {
                    it.copy(
                        submitting = false,
                        submitted = true,
                        publishedPostId = publishedPostId,
                        errorMessage = null,
                    )
                }
                logClientInfo("feedback.submit", "success feedbackId=$feedbackId postId=${publishedPostId ?: -1}")
            } catch (e: Exception) {
                logClientError(
                    scope = "feedback.submit",
                    throwable = e,
                    userHint = "反馈提交失败",
                )
                _feedbackUiState.update {
                    it.copy(
                        submitting = false,
                        submitted = false,
                        errorMessage = "反馈提交失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun finishFeedbackFlow() {
        _feedbackUiState.value = FeedbackUiState()
        _retouchUiState.value = RetouchUiState()
        _uiState.update { it.copy(statusText = "反馈已提交，感谢支持", showPostCaptureChoice = false) }
    }

    fun clearCommunityError() {
        _communityUiState.update { it.copy(errorMessage = null) }
    }

    fun updateDirectPublishImageUri(uri: String?) {
        _communityUiState.update { it.copy(publishImageUri = uri, errorMessage = null) }
    }

    fun useLatestPhotoForDirectPublish() {
        val latest = _uiState.value.lastPhotoUri
            ?: _feedbackUiState.value.photoUri
            ?: _retouchUiState.value.originalPhotoUri
        if (latest.isNullOrBlank()) {
            _communityUiState.update { it.copy(errorMessage = "暂无最近拍摄照片，请先拍照或从相册选择") }
            return
        }
        _communityUiState.update { it.copy(publishImageUri = latest, errorMessage = null) }
    }

    fun updateDirectPublishPlaceTag(value: String) {
        _communityUiState.update { it.copy(publishPlaceTag = value.take(48), errorMessage = null) }
    }

    fun updateDirectPublishSceneType(value: String) {
        val normalized = value.trim().lowercase().ifBlank { "general" }
        _communityUiState.update { it.copy(publishSceneType = normalized, errorMessage = null) }
    }

    fun updateDirectPublishCaption(value: String) {
        _communityUiState.update { it.copy(publishCaption = value.take(280), errorMessage = null) }
    }

    fun updateDirectPublishReviewText(value: String) {
        _communityUiState.update { it.copy(publishReviewText = value.take(280), errorMessage = null) }
    }

    fun updateDirectPublishRating(value: Int?) {
        val normalized = value?.coerceIn(1, 5)
        _communityUiState.update { it.copy(publishRating = normalized, errorMessage = null) }
    }

    fun startRelayFromPost(parentPostId: Int) {
        _communityUiState.update {
            it.copy(
                publishPostType = "relay",
                publishRelayParentPostId = parentPostId,
                errorMessage = null,
            )
        }
    }

    fun resetDirectPublishToNormal() {
        _communityUiState.update {
            it.copy(
                publishPostType = "normal",
                publishRelayParentPostId = null,
                publishStyleTemplatePostId = null,
                errorMessage = null,
            )
        }
    }

    fun usePostAsTemplate(templatePostId: Int) {
        _communityUiState.update {
            it.copy(
                publishStyleTemplatePostId = templatePostId,
                errorMessage = null,
            )
        }
    }

    fun publishDirectPost() {
        val state = _communityUiState.value
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        if (state.publishImageUri.isNullOrBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先选择要发布的照片") }
            return
        }
        if (state.publishPlaceTag.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请填写地点标签") }
            return
        }
        if (state.publishPostType == "relay" && (state.publishRelayParentPostId ?: 0) <= 0) {
            _communityUiState.update { it.copy(errorMessage = "接力发布需要先选择来源帖子") }
            return
        }
        if (state.publishingDirect) return

        viewModelScope.launch {
            _communityUiState.update { it.copy(publishingDirect = true, errorMessage = null) }
            try {
                val imageBase64 = encodePhotoUriToBase64(state.publishImageUri)
                val post = if (state.publishPostType == "relay" && (state.publishRelayParentPostId ?: 0) > 0) {
                    communityApiClient.publishRelayPost(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        imageBase64 = imageBase64,
                        placeTag = state.publishPlaceTag.trim(),
                        sceneType = state.publishSceneType,
                        caption = state.publishCaption.trim(),
                        reviewText = state.publishReviewText.trim(),
                        rating = state.publishRating,
                        relayParentPostId = state.publishRelayParentPostId ?: 0,
                        styleTemplatePostId = state.publishStyleTemplatePostId,
                    )
                } else {
                    communityApiClient.publishDirectPost(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        imageBase64 = imageBase64,
                        placeTag = state.publishPlaceTag.trim(),
                        sceneType = state.publishSceneType,
                        caption = state.publishCaption.trim(),
                        reviewText = state.publishReviewText.trim(),
                        rating = state.publishRating,
                        postType = "normal",
                        relayParentPostId = null,
                        styleTemplatePostId = state.publishStyleTemplatePostId,
                    )
                }
                _communityUiState.update {
                    it.copy(
                        publishingDirect = false,
                        publishCaption = "",
                        publishReviewText = "",
                        publishRating = null,
                        publishPostType = "normal",
                        publishRelayParentPostId = null,
                        publishStyleTemplatePostId = null,
                        referencePostId = post.id.takeIf { id -> id > 0 } ?: it.referencePostId,
                        errorMessage = null,
                    )
                }
                refreshCommunityFeed(reset = true)
            } catch (e: Exception) {
                logClientError(
                    scope = "community.publish.direct",
                    throwable = e,
                    userHint = "社区发布失败",
                )
                _communityUiState.update {
                    it.copy(
                        publishingDirect = false,
                        errorMessage = "发布失败，请检查网络后重试",
                    )
                }
            }
        }
    }

    fun refreshCommunityFeed(reset: Boolean = true) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val previousFeed = _communityUiState.value.feed
        if (reset) {
            communityFeedOffset = 0
            _communityUiState.update {
                it.copy(
                    loadingFeed = true,
                    errorMessage = null,
                )
            }
        } else {
            _communityUiState.update { it.copy(loadingFeed = true, errorMessage = null) }
        }

        viewModelScope.launch {
            try {
                val currentFeed = if (reset) emptyList() else _communityUiState.value.feed
                val result = communityApiClient.fetchFeed(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    offset = communityFeedOffset,
                    limit = 20,
                )
                val mergedFromServer = if (reset) {
                    result.items.map { it.toCommunityPost(_uiState.value.settings.serverUrl) }
                } else {
                    val appended = result.items.map { it.toCommunityPost(_uiState.value.settings.serverUrl) }
                    (currentFeed + appended).distinctBy { it.id }
                }
                communityFeedOffset = result.nextOffset
                _communityUiState.update {
                    it.copy(
                        loadingFeed = false,
                        feed = mergedFromServer,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.feed",
                    throwable = e,
                    userHint = "加载社区内容失败",
                )
                _communityUiState.update {
                    if (reset) {
                        it.copy(
                            loadingFeed = false,
                            feed = previousFeed,
                            errorMessage = "加载社区内容失败，请下拉重试",
                        )
                    } else {
                        it.copy(
                            loadingFeed = false,
                            errorMessage = "加载社区内容失败，请稍后重试",
                        )
                    }
                }
            }
        }
    }

    fun updateRecommendationPlaceTag(value: String) {
        _communityUiState.update { it.copy(recommendationPlaceTag = value.take(48)) }
    }

    fun updateRecommendationSceneType(value: String) {
        val scene = value.trim().lowercase().ifBlank { "general" }
        _communityUiState.update { it.copy(recommendationSceneType = scene) }
    }

    fun refreshRecommendations() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        // V1 仅支持地点 + 场景类型维度推荐。
        // 姿势（pose）维度留到后续版本，不在本次请求参数内。
        val snapshot = _communityUiState.value
        _communityUiState.update { it.copy(loadingRecommendations = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val items = communityApiClient.fetchRecommendations(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    placeTag = snapshot.recommendationPlaceTag,
                    sceneType = snapshot.recommendationSceneType,
                    limit = 12,
                )
                _communityUiState.update {
                    it.copy(
                        loadingRecommendations = false,
                        recommendations = items.map { item ->
                            CommunityRecommendationItem(
                                post = item.post.toCommunityPost(_uiState.value.settings.serverUrl),
                                score = item.score,
                                reason = item.reason,
                            )
                        },
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.recommendations",
                    throwable = e,
                    userHint = "推荐加载失败",
                )
                _communityUiState.update {
                    it.copy(
                        loadingRecommendations = false,
                        errorMessage = "推荐加载失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun togglePostLike(post: CommunityPostItem) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val postId = post.id
        if (_communityUiState.value.likingPostIds.contains(postId)) return

        _communityUiState.update {
            it.copy(likingPostIds = it.likingPostIds + postId, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                val liked = if (post.likedByMe) {
                    communityApiClient.unlikePost(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        postId = postId,
                    )
                } else {
                    communityApiClient.likePost(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        postId = postId,
                    )
                }
                _communityUiState.update { state ->
                    state.copy(
                        likingPostIds = state.likingPostIds - postId,
                        feed = state.feed.map { item ->
                            if (item.id != postId) item else item.copy(
                                likedByMe = liked.liked,
                                likeCount = liked.likeCount.coerceAtLeast(0),
                            )
                        },
                        recommendations = state.recommendations.map { rec ->
                            if (rec.post.id != postId) rec else rec.copy(
                                post = rec.post.copy(
                                    likedByMe = liked.liked,
                                    likeCount = liked.likeCount.coerceAtLeast(0),
                                ),
                            )
                        },
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.like.toggle",
                    throwable = e,
                    userHint = "互动失败",
                )
                _communityUiState.update {
                    it.copy(
                        likingPostIds = it.likingPostIds - postId,
                        errorMessage = "点赞操作失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun updateCommentDraft(postId: Int, value: String) {
        _communityUiState.update {
            it.copy(
                commentDraftByPost = it.commentDraftByPost + (postId to value.take(280)),
                errorMessage = null,
            )
        }
    }

    fun loadComments(postId: Int) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        _communityUiState.update { it.copy(loadingCommentsPostId = postId, errorMessage = null) }
        viewModelScope.launch {
            try {
                val comments = communityApiClient.fetchComments(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    postId = postId,
                ).map { it.toCommunityComment() }
                _communityUiState.update {
                    it.copy(
                        loadingCommentsPostId = null,
                        commentsByPost = it.commentsByPost + (postId to comments),
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.comments.load",
                    throwable = e,
                    userHint = "评论加载失败",
                )
                _communityUiState.update {
                    it.copy(
                        loadingCommentsPostId = null,
                        errorMessage = "评论加载失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun submitComment(postId: Int) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val text = _communityUiState.value.commentDraftByPost[postId]?.trim().orEmpty()
        if (text.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请输入评论内容") }
            return
        }
        viewModelScope.launch {
            try {
                val comment = communityApiClient.addComment(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    postId = postId,
                    text = text,
                ).toCommunityComment()
                _communityUiState.update { state ->
                    val currentComments = state.commentsByPost[postId].orEmpty()
                    val updatedComments = listOf(comment) + currentComments
                    state.copy(
                        commentsByPost = state.commentsByPost + (postId to updatedComments),
                        commentDraftByPost = state.commentDraftByPost + (postId to ""),
                        feed = state.feed.map { post ->
                            if (post.id != postId) post else post.copy(commentCount = post.commentCount + 1)
                        },
                        recommendations = state.recommendations.map { rec ->
                            if (rec.post.id != postId) rec else rec.copy(
                                post = rec.post.copy(commentCount = rec.post.commentCount + 1),
                            )
                        },
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.comments.add",
                    throwable = e,
                    userHint = "评论发送失败",
                )
                _communityUiState.update { it.copy(errorMessage = "评论发送失败，请稍后重试") }
            }
        }
    }

    fun deleteComment(postId: Int, commentId: Int) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        viewModelScope.launch {
            try {
                communityApiClient.deleteComment(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    commentId = commentId,
                )
                _communityUiState.update { state ->
                    val currentComments = state.commentsByPost[postId].orEmpty()
                    val updated = currentComments.filterNot { it.id == commentId }
                    val delta = 1
                    state.copy(
                        commentsByPost = state.commentsByPost + (postId to updated),
                        feed = state.feed.map { post ->
                            if (post.id != postId) post else post.copy(
                                commentCount = (post.commentCount - delta).coerceAtLeast(0),
                            )
                        },
                        recommendations = state.recommendations.map { rec ->
                            if (rec.post.id != postId) rec else rec.copy(
                                post = rec.post.copy(
                                    commentCount = (rec.post.commentCount - delta).coerceAtLeast(0),
                                ),
                            )
                        },
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.comments.delete",
                    throwable = e,
                    userHint = "评论删除失败",
                )
                _communityUiState.update { it.copy(errorMessage = "评论删除失败，请稍后重试") }
            }
        }
    }

    fun requestRemakeGuide(templatePostId: Int) {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        _communityUiState.update { it.copy(remakeLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val guide = communityApiClient.fetchRemakeGuide(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    templatePostId = templatePostId,
                ).toCommunityRemakeGuide(_uiState.value.settings.serverUrl)
                _communityUiState.update {
                    it.copy(
                        remakeLoading = false,
                        remakeGuide = guide,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.remake.guide",
                    throwable = e,
                    userHint = "同款复刻指引获取失败",
                )
                _communityUiState.update {
                    it.copy(
                        remakeLoading = false,
                        errorMessage = "同款复刻指引获取失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun applyRemakeGuideToCamera() {
        val guide = _communityUiState.value.remakeGuide ?: return
        _uiState.update {
            it.copy(
                statusText = "同款复刻已加载：${guide.cameraHint}",
                tipText = guide.shotScript.firstOrNull() ?: it.tipText,
            )
        }
        val recommendedMode = when (guide.templatePost.sceneType.lowercase()) {
            "portrait" -> CaptureMode.PORTRAIT
            "food" -> CaptureMode.FOOD
            else -> CaptureMode.GENERAL
        }
        updateCaptureMode(recommendedMode)
    }

    fun selectCommunityReferencePost(postId: Int?) {
        _communityUiState.update { it.copy(referencePostId = postId, errorMessage = null) }
    }

    fun updateCommunityPersonImageUri(uri: String?) {
        _communityUiState.update { it.copy(personImageUri = uri, errorMessage = null) }
    }

    fun updateCommunityComposeStrength(value: Float) {
        _communityUiState.update { it.copy(composeStrength = value.coerceIn(0f, 1f)) }
    }

    fun clearComposedPreview() {
        composeJobPolling?.cancel()
        composeJobPolling = null
        _communityUiState.update {
            it.copy(
                composedPreviewBase64 = null,
                composeCompareInputBase64 = null,
                composeJobId = null,
                composeJobStatus = "",
                composeJobProgress = 0,
                composeJobPriority = 100,
                composeRetryCount = 0,
                composeMaxRetries = 0,
                composeNextRetryAt = null,
                composeStartedAt = null,
                composeHeartbeatAt = null,
                composeLeaseExpiresAt = null,
                composeCancelReason = "",
                composeImplementationStatus = "ready",
                composePlaceholderNotes = emptyList(),
                composeErrorMessage = "",
                composeRequestId = "",
                errorMessage = null,
            )
        }
    }

    fun composeCommunityImage() {
        val state = _communityUiState.value
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val referenceId = state.referencePostId
        if (referenceId == null || referenceId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "请先选择参考照片") }
            return
        }
        val personUri = state.personImageUri
        if (personUri.isNullOrBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先选择半身或全身照") }
            return
        }
        if (state.composing) return

        viewModelScope.launch {
            _communityUiState.update {
                it.copy(
                    composing = true,
                    composedPreviewBase64 = null,
                    composeCompareInputBase64 = null,
                    composeJobId = null,
                    composeJobStatus = "queued",
                    composeJobProgress = 0,
                    composeJobPriority = 100,
                    composeRetryCount = 0,
                    composeMaxRetries = 0,
                    composeNextRetryAt = null,
                    composeStartedAt = null,
                    composeHeartbeatAt = null,
                    composeLeaseExpiresAt = null,
                    composeCancelReason = "",
                    composeImplementationStatus = "ready",
                    composePlaceholderNotes = emptyList(),
                    composeErrorMessage = "",
                    composeRequestId = "",
                    errorMessage = null,
                )
            }
            try {
                val personBase64 = encodePhotoUriToBase64(personUri)
                val job = communityApiClient.createComposeJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    referencePostId = referenceId,
                    personImageBase64 = personBase64,
                    strength = state.composeStrength,
                )
                _communityUiState.update { current ->
                    current.copy(
                        composeJobId = job.jobId,
                        composeJobStatus = job.status,
                        composeJobProgress = job.progress.coerceIn(0, 100),
                        composeJobPriority = job.priority.coerceIn(1, 999),
                        composeRetryCount = job.retryCount.coerceAtLeast(0),
                        composeMaxRetries = job.maxRetries.coerceAtLeast(0),
                        composeNextRetryAt = job.nextRetryAt,
                        composeStartedAt = job.startedAt,
                        composeHeartbeatAt = job.heartbeatAt,
                        composeLeaseExpiresAt = job.leaseExpiresAt,
                        composeCancelReason = job.cancelReason,
                        composeRequestId = if (job.requestId.isBlank()) current.composeRequestId else job.requestId,
                    )
                }
                launchComposeJobPolling(job.jobId)
            } catch (e: Exception) {
                logClientError(
                    scope = "community.compose",
                    throwable = e,
                    userHint = "AI 融合失败",
                )
                _communityUiState.update {
                    it.copy(
                        composing = false,
                        composeJobStatus = "failed",
                        composeErrorMessage = "AI 融合任务提交失败",
                        errorMessage = "AI 融合失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun retryComposeJob() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val jobId = _communityUiState.value.composeJobId
        if (jobId == null || jobId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "当前没有可重试的融合任务") }
            return
        }
        viewModelScope.launch {
            _communityUiState.update {
                it.copy(
                    composing = true,
                    composeJobStatus = "queued",
                    composeJobProgress = 0,
                    composeNextRetryAt = null,
                    composeStartedAt = null,
                    composeHeartbeatAt = null,
                    composeLeaseExpiresAt = null,
                    composeCancelReason = "",
                    composeErrorMessage = "",
                    errorMessage = null,
                )
            }
            try {
                val retried = communityApiClient.retryCreativeJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    jobId = jobId,
                )
                _communityUiState.update { current ->
                    current.copy(
                        composeJobStatus = retried.status,
                        composeJobProgress = retried.progress.coerceIn(0, 100),
                        composeJobPriority = retried.priority.coerceIn(1, 999),
                        composeRetryCount = retried.retryCount.coerceAtLeast(0),
                        composeMaxRetries = retried.maxRetries.coerceAtLeast(0),
                        composeNextRetryAt = retried.nextRetryAt,
                        composeStartedAt = retried.startedAt,
                        composeHeartbeatAt = retried.heartbeatAt,
                        composeLeaseExpiresAt = retried.leaseExpiresAt,
                        composeCancelReason = retried.cancelReason,
                        composeRequestId = if (retried.requestId.isBlank()) current.composeRequestId else retried.requestId,
                    )
                }
                launchComposeJobPolling(jobId)
            } catch (e: Exception) {
                logClientError(
                    scope = "community.compose.retry",
                    throwable = e,
                    userHint = "AI 融合重试失败",
                )
                _communityUiState.update {
                    it.copy(
                        composing = false,
                        composeJobStatus = "failed",
                        composeErrorMessage = "重试失败",
                        errorMessage = "AI 融合重试失败，请稍后再试",
                    )
                }
            }
        }
    }

    fun cancelComposeJob() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val jobId = _communityUiState.value.composeJobId
        if (jobId == null || jobId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "当前没有可取消的融合任务") }
            return
        }
        viewModelScope.launch {
            try {
                val canceled = communityApiClient.cancelCreativeJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    jobId = jobId,
                )
                composeJobPolling?.cancel()
                composeJobPolling = null
                _communityUiState.update { current ->
                    current.copy(
                        composing = false,
                        composeJobStatus = canceled.status,
                        composeJobProgress = canceled.progress.coerceIn(0, 100),
                        composeJobPriority = canceled.priority.coerceIn(1, 999),
                        composeRetryCount = canceled.retryCount.coerceAtLeast(0),
                        composeMaxRetries = canceled.maxRetries.coerceAtLeast(0),
                        composeNextRetryAt = canceled.nextRetryAt,
                        composeStartedAt = canceled.startedAt,
                        composeHeartbeatAt = canceled.heartbeatAt,
                        composeLeaseExpiresAt = canceled.leaseExpiresAt,
                        composeCancelReason = canceled.cancelReason,
                        composeErrorMessage = "任务已取消",
                        errorMessage = "已取消融合任务",
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.compose.cancel",
                    throwable = e,
                    userHint = "取消融合任务失败",
                )
                _communityUiState.update {
                    it.copy(errorMessage = "取消融合任务失败，请稍后重试")
                }
            }
        }
    }

    fun saveComposedPreviewToGallery(onSaved: (String?) -> Unit = {}) {
        val preview = _communityUiState.value.composedPreviewBase64
        if (preview.isNullOrBlank()) {
            onSaved(null)
            return
        }
        viewModelScope.launch {
            val uri = try {
                saveBase64ToGallery(preview, "CamMate_CommunityCompose")
            } catch (e: Exception) {
                logClientError(
                    scope = "community.compose.save",
                    throwable = e,
                    userHint = "融合结果保存失败",
                )
                null
            }
            onSaved(uri)
        }
    }

    fun updateCocreatePersonAUri(uri: String?) {
        _communityUiState.update { it.copy(cocreatePersonAUri = uri, errorMessage = null) }
    }

    fun updateCocreatePersonBUri(uri: String?) {
        _communityUiState.update { it.copy(cocreatePersonBUri = uri, errorMessage = null) }
    }

    fun clearCocreatePreview() {
        cocreateJobPolling?.cancel()
        cocreateJobPolling = null
        _communityUiState.update {
            it.copy(
                cocreatePreviewBase64 = null,
                cocreateCompareInputBase64 = null,
                cocreateJobId = null,
                cocreateJobStatus = "",
                cocreateJobProgress = 0,
                cocreateJobPriority = 100,
                cocreateRetryCount = 0,
                cocreateMaxRetries = 0,
                cocreateNextRetryAt = null,
                cocreateStartedAt = null,
                cocreateHeartbeatAt = null,
                cocreateLeaseExpiresAt = null,
                cocreateCancelReason = "",
                cocreateImplementationStatus = "ready",
                cocreatePlaceholderNotes = emptyList(),
                cocreateErrorMessage = "",
                cocreateRequestId = "",
                errorMessage = null,
            )
        }
    }

    fun composeCocreateImage() {
        val state = _communityUiState.value
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val referenceId = state.referencePostId
        if (referenceId == null || referenceId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "请先选择社区参考图") }
            return
        }
        if (state.cocreating) return
        val personA = state.cocreatePersonAUri
        val personB = state.cocreatePersonBUri
        if (personA.isNullOrBlank() || personB.isNullOrBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先选择两张人物照片") }
            return
        }

        viewModelScope.launch {
            _communityUiState.update {
                it.copy(
                    cocreating = true,
                    cocreatePreviewBase64 = null,
                    cocreateCompareInputBase64 = null,
                    cocreateJobId = null,
                    cocreateJobStatus = "queued",
                    cocreateJobProgress = 0,
                    cocreateJobPriority = 100,
                    cocreateRetryCount = 0,
                    cocreateMaxRetries = 0,
                    cocreateNextRetryAt = null,
                    cocreateStartedAt = null,
                    cocreateHeartbeatAt = null,
                    cocreateLeaseExpiresAt = null,
                    cocreateCancelReason = "",
                    cocreateImplementationStatus = "ready",
                    cocreatePlaceholderNotes = emptyList(),
                    cocreateErrorMessage = "",
                    cocreateRequestId = "",
                    errorMessage = null,
                )
            }
            try {
                val personABase64 = encodePhotoUriToBase64(personA)
                val personBBase64 = encodePhotoUriToBase64(personB)
                val job = communityApiClient.createCocreateJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    referencePostId = referenceId,
                    personAImageBase64 = personABase64,
                    personBImageBase64 = personBBase64,
                    strength = state.composeStrength,
                )
                _communityUiState.update { current ->
                    current.copy(
                        cocreateJobId = job.jobId,
                        cocreateJobStatus = job.status,
                        cocreateJobProgress = job.progress.coerceIn(0, 100),
                        cocreateJobPriority = job.priority.coerceIn(1, 999),
                        cocreateRetryCount = job.retryCount.coerceAtLeast(0),
                        cocreateMaxRetries = job.maxRetries.coerceAtLeast(0),
                        cocreateNextRetryAt = job.nextRetryAt,
                        cocreateStartedAt = job.startedAt,
                        cocreateHeartbeatAt = job.heartbeatAt,
                        cocreateLeaseExpiresAt = job.leaseExpiresAt,
                        cocreateCancelReason = job.cancelReason,
                        cocreateRequestId = if (job.requestId.isBlank()) current.cocreateRequestId else job.requestId,
                    )
                }
                launchCocreateJobPolling(job.jobId)
            } catch (e: Exception) {
                logClientError(
                    scope = "community.cocreate.compose",
                    throwable = e,
                    userHint = "双人共创失败",
                )
                _communityUiState.update {
                    it.copy(
                        cocreating = false,
                        cocreateJobStatus = "failed",
                        cocreateErrorMessage = "双人共创任务提交失败",
                        errorMessage = "双人共创失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun retryCocreateJob() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val jobId = _communityUiState.value.cocreateJobId
        if (jobId == null || jobId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "当前没有可重试的共创任务") }
            return
        }
        viewModelScope.launch {
            _communityUiState.update {
                it.copy(
                    cocreating = true,
                    cocreateJobStatus = "queued",
                    cocreateJobProgress = 0,
                    cocreateNextRetryAt = null,
                    cocreateStartedAt = null,
                    cocreateHeartbeatAt = null,
                    cocreateLeaseExpiresAt = null,
                    cocreateCancelReason = "",
                    cocreateErrorMessage = "",
                    errorMessage = null,
                )
            }
            try {
                val retried = communityApiClient.retryCreativeJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    jobId = jobId,
                )
                _communityUiState.update { current ->
                    current.copy(
                        cocreateJobStatus = retried.status,
                        cocreateJobProgress = retried.progress.coerceIn(0, 100),
                        cocreateJobPriority = retried.priority.coerceIn(1, 999),
                        cocreateRetryCount = retried.retryCount.coerceAtLeast(0),
                        cocreateMaxRetries = retried.maxRetries.coerceAtLeast(0),
                        cocreateNextRetryAt = retried.nextRetryAt,
                        cocreateStartedAt = retried.startedAt,
                        cocreateHeartbeatAt = retried.heartbeatAt,
                        cocreateLeaseExpiresAt = retried.leaseExpiresAt,
                        cocreateCancelReason = retried.cancelReason,
                        cocreateRequestId = if (retried.requestId.isBlank()) current.cocreateRequestId else retried.requestId,
                    )
                }
                launchCocreateJobPolling(jobId)
            } catch (e: Exception) {
                logClientError(
                    scope = "community.cocreate.retry",
                    throwable = e,
                    userHint = "双人共创重试失败",
                )
                _communityUiState.update {
                    it.copy(
                        cocreating = false,
                        cocreateJobStatus = "failed",
                        cocreateErrorMessage = "重试失败",
                        errorMessage = "双人共创重试失败，请稍后再试",
                    )
                }
            }
        }
    }

    fun cancelCocreateJob() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _communityUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }
        val jobId = _communityUiState.value.cocreateJobId
        if (jobId == null || jobId <= 0) {
            _communityUiState.update { it.copy(errorMessage = "当前没有可取消的共创任务") }
            return
        }
        viewModelScope.launch {
            try {
                val canceled = communityApiClient.cancelCreativeJob(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    jobId = jobId,
                )
                cocreateJobPolling?.cancel()
                cocreateJobPolling = null
                _communityUiState.update { current ->
                    current.copy(
                        cocreating = false,
                        cocreateJobStatus = canceled.status,
                        cocreateJobProgress = canceled.progress.coerceIn(0, 100),
                        cocreateJobPriority = canceled.priority.coerceIn(1, 999),
                        cocreateRetryCount = canceled.retryCount.coerceAtLeast(0),
                        cocreateMaxRetries = canceled.maxRetries.coerceAtLeast(0),
                        cocreateNextRetryAt = canceled.nextRetryAt,
                        cocreateStartedAt = canceled.startedAt,
                        cocreateHeartbeatAt = canceled.heartbeatAt,
                        cocreateLeaseExpiresAt = canceled.leaseExpiresAt,
                        cocreateCancelReason = canceled.cancelReason,
                        cocreateErrorMessage = "任务已取消",
                        errorMessage = "已取消共创任务",
                    )
                }
            } catch (e: Exception) {
                logClientError(
                    scope = "community.cocreate.cancel",
                    throwable = e,
                    userHint = "取消共创任务失败",
                )
                _communityUiState.update {
                    it.copy(errorMessage = "取消共创任务失败，请稍后重试")
                }
            }
        }
    }

    private fun launchComposeJobPolling(jobId: Int) {
        composeJobPolling?.cancel()
        composeJobPolling = viewModelScope.launch {
            while (true) {
                val job = try {
                    communityApiClient.getCreativeJob(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        jobId = jobId,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    logClientError(
                        scope = "community.compose.job.poll",
                        throwable = e,
                        userHint = "AI 融合任务查询失败",
                    )
                    _communityUiState.update {
                        it.copy(
                            composing = false,
                            composeJobStatus = "failed",
                            composeErrorMessage = "任务状态查询失败",
                            errorMessage = "AI 融合任务状态获取失败，请稍后重试",
                        )
                    }
                    return@launch
                }

                val status = job.status.lowercase()
                _communityUiState.update { state ->
                    state.copy(
                        composeJobId = job.jobId,
                        composeJobStatus = status,
                        composeJobProgress = job.progress.coerceIn(0, 100),
                        composeRetryCount = job.retryCount.coerceAtLeast(0),
                        composeMaxRetries = job.maxRetries.coerceAtLeast(0),
                        composeNextRetryAt = job.nextRetryAt,
                        composeJobPriority = job.priority.coerceIn(1, 999),
                        composeStartedAt = job.startedAt,
                        composeHeartbeatAt = job.heartbeatAt,
                        composeLeaseExpiresAt = job.leaseExpiresAt,
                        composeCancelReason = job.cancelReason,
                        composeImplementationStatus = job.implementationStatus,
                        composePlaceholderNotes = job.placeholderNotes,
                        composeErrorMessage = if (status == "failed" || status == "canceled") {
                            if (job.errorMessage.isBlank()) "AI 融合任务失败" else job.errorMessage
                        } else {
                            ""
                        },
                        composeRequestId = if (job.requestId.isBlank()) state.composeRequestId else job.requestId,
                    )
                }

                when (status) {
                    "queued", "running" -> delay(1200)
                    "success" -> {
                        _communityUiState.update { state ->
                            val image = job.composedImageBase64
                            if (image.isNullOrBlank()) {
                                state.copy(
                                    composing = false,
                                    composeJobStatus = "failed",
                                    composeErrorMessage = "任务完成但未返回图片",
                                    errorMessage = "AI 融合未返回有效图片，请重试",
                                )
                            } else {
                                state.copy(
                                    composing = false,
                                    composedPreviewBase64 = image,
                                    composeCompareInputBase64 = job.compareInputBase64,
                                    errorMessage = null,
                                )
                            }
                        }
                        return@launch
                    }
                    "failed" -> {
                        _communityUiState.update {
                            it.copy(
                                composing = false,
                                errorMessage = if (job.errorMessage.isBlank()) "AI 融合任务失败，可重试" else job.errorMessage,
                            )
                        }
                        return@launch
                    }
                    "canceled" -> {
                        _communityUiState.update {
                            it.copy(
                                composing = false,
                                errorMessage = "融合任务已取消",
                            )
                        }
                        return@launch
                    }
                    else -> delay(1200)
                }
            }
        }
    }

    private fun launchCocreateJobPolling(jobId: Int) {
        cocreateJobPolling?.cancel()
        cocreateJobPolling = viewModelScope.launch {
            while (true) {
                val job = try {
                    communityApiClient.getCreativeJob(
                        serverUrl = _uiState.value.settings.serverUrl,
                        bearerToken = bearerToken,
                        jobId = jobId,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    logClientError(
                        scope = "community.cocreate.job.poll",
                        throwable = e,
                        userHint = "双人共创任务查询失败",
                    )
                    _communityUiState.update {
                        it.copy(
                            cocreating = false,
                            cocreateJobStatus = "failed",
                            cocreateErrorMessage = "任务状态查询失败",
                            errorMessage = "双人共创任务状态获取失败，请稍后重试",
                        )
                    }
                    return@launch
                }

                val status = job.status.lowercase()
                _communityUiState.update { state ->
                    state.copy(
                        cocreateJobId = job.jobId,
                        cocreateJobStatus = status,
                        cocreateJobProgress = job.progress.coerceIn(0, 100),
                        cocreateRetryCount = job.retryCount.coerceAtLeast(0),
                        cocreateMaxRetries = job.maxRetries.coerceAtLeast(0),
                        cocreateNextRetryAt = job.nextRetryAt,
                        cocreateJobPriority = job.priority.coerceIn(1, 999),
                        cocreateStartedAt = job.startedAt,
                        cocreateHeartbeatAt = job.heartbeatAt,
                        cocreateLeaseExpiresAt = job.leaseExpiresAt,
                        cocreateCancelReason = job.cancelReason,
                        cocreateImplementationStatus = job.implementationStatus,
                        cocreatePlaceholderNotes = job.placeholderNotes,
                        cocreateErrorMessage = if (status == "failed" || status == "canceled") {
                            if (job.errorMessage.isBlank()) "双人共创任务失败" else job.errorMessage
                        } else {
                            ""
                        },
                        cocreateRequestId = if (job.requestId.isBlank()) state.cocreateRequestId else job.requestId,
                    )
                }

                when (status) {
                    "queued", "running" -> delay(1200)
                    "success" -> {
                        _communityUiState.update { state ->
                            val image = job.composedImageBase64
                            if (image.isNullOrBlank()) {
                                state.copy(
                                    cocreating = false,
                                    cocreateJobStatus = "failed",
                                    cocreateErrorMessage = "任务完成但未返回图片",
                                    errorMessage = "双人共创未返回有效图片，请重试",
                                )
                            } else {
                                state.copy(
                                    cocreating = false,
                                    cocreatePreviewBase64 = image,
                                    cocreateCompareInputBase64 = job.compareInputBase64,
                                    errorMessage = null,
                                )
                            }
                        }
                        return@launch
                    }
                    "failed" -> {
                        _communityUiState.update {
                            it.copy(
                                cocreating = false,
                                errorMessage = if (job.errorMessage.isBlank()) "双人共创任务失败，可重试" else job.errorMessage,
                            )
                        }
                        return@launch
                    }
                    "canceled" -> {
                        _communityUiState.update {
                            it.copy(
                                cocreating = false,
                                errorMessage = "共创任务已取消",
                            )
                        }
                        return@launch
                    }
                    else -> delay(1200)
                }
            }
        }
    }

    fun saveCocreatePreviewToGallery(onSaved: (String?) -> Unit = {}) {
        val preview = _communityUiState.value.cocreatePreviewBase64
        if (preview.isNullOrBlank()) {
            onSaved(null)
            return
        }
        viewModelScope.launch {
            val uri = try {
                saveBase64ToGallery(preview, "CamMate_Cocreate")
            } catch (e: Exception) {
                logClientError(
                    scope = "community.cocreate.save",
                    throwable = e,
                    userHint = "双人共创结果保存失败",
                )
                null
            }
            onSaved(uri)
        }
    }

    private fun encodePhotoUriToBase64(photoUri: String): String {
        val app = getApplication<Application>()
        val uri = Uri.parse(photoUri)
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取原图")
        if (bytes.isEmpty()) {
            throw IllegalStateException("原图内容为空")
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun saveBase64ToGallery(base64Data: String, prefix: String): String? {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        if (bytes.isEmpty()) return null

        val app = getApplication<Application>()
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_$fileName")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamMate")
        }

        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(bytes) } ?: return null
        return uri.toString()
    }

    private fun computeRotatedAspectRatio(width: Int, height: Int, rotationDegrees: Int): Float {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val finalWidth = if (swap) safeHeight else safeWidth
        val finalHeight = if (swap) safeWidth else safeHeight
        return finalWidth.toFloat() / finalHeight.toFloat()
    }

    private fun statusByProvider(provider: GuideProvider, aiEnabled: Boolean): String {
        if (!aiEnabled) return "识别已关闭"
        return "云端分析可用"
    }

    private fun userFriendlyAuthError(throwable: Throwable?, action: String): String {
        val raw = throwable?.message.orEmpty().lowercase()
        return when {
            "email already registered" in raw -> "该邮箱已注册，请直接登录"
            "invalid email" in raw -> "邮箱格式不正确，请检查 @ 和域名后缀"
            "string_too_short" in raw && "\"password\"" in raw -> "密码至少 6 位"
            "string_too_long" in raw && "\"nickname\"" in raw -> "昵称最多 32 个字符"
            "invalid credentials" in raw -> "账号或密码错误"
            "timeout" in raw || "timed out" in raw -> "网络连接超时，请检查网络后重试"
            "failed to connect" in raw || "unable to resolve host" in raw -> "无法连接服务器，请检查网络或服务地址"
            action == "login" -> "登录失败，请检查账号或网络后重试"
            else -> "注册失败，请检查信息后重试"
        }
    }

    private fun userFriendlyRetouchError(throwable: Throwable?): String {
        val raw = throwable?.message.orEmpty()
        val lowered = raw.lowercase()
        val reqId = extractRequestId(throwable)
        val suffix = reqId.takeIf { it.isNotBlank() }?.let { "（req=$it）" }.orEmpty()
        val base = when {
            "ark_image_api_key missing" in lowered -> "修图服务未配置 ARK_IMAGE_API_KEY"
            "http 401" in lowered || "http 403" in lowered -> "修图鉴权失败，请检查修图 API Key"
            "http 429" in lowered -> "修图服务限流，请稍后重试"
            "timed out" in lowered || "timeout" in lowered -> "修图超时，请稍后重试"
            "invalid image_base64" in lowered || "invalid image bytes" in lowered -> "图片格式异常，请重新拍照后重试"
            "http 5" in lowered -> "修图服务暂时不可用，请稍后重试"
            else -> "AI 修图失败，请检查网络和服务配置"
        }
        return base + suffix
    }

    private fun extractRequestId(throwable: Throwable?): String {
        val text = throwable?.message.orEmpty()
        val match = Regex("""req=([A-Za-z0-9_\-]+)""").find(text) ?: return ""
        return match.groupValues.getOrNull(1).orEmpty()
    }

    private fun logClientError(scope: String, throwable: Throwable?, userHint: String) {
        val rawMessage = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "no throwable"
        if (throwable != null) {
            appLogger.e(TAG, "[$scope] $userHint | $rawMessage", throwable)
        } else {
            appLogger.e(TAG, "[$scope] $userHint | $rawMessage")
        }
        if (_uiState.value.settings.debugEnabled) {
            _uiState.update { state ->
                val merged = listOf(state.debugRaw, "ERR[$scope] $rawMessage")
                    .filter { it.isNotBlank() }
                    .takeLast(12)
                    .joinToString("\n")
                state.copy(debugRaw = merged.takeLast(4000))
            }
        }
    }

    private fun logClientInfo(scope: String, message: String) {
        appLogger.i(TAG, "[$scope] $message")
    }

    private fun syncCommunityAuthHeader() {
        _communityUiState.update {
            it.copy(
                authHeader = if (bearerToken.isNotBlank()) "Bearer $bearerToken" else "",
            )
        }
    }

    private fun CommunityPostDto.toCommunityPost(serverUrl: String): CommunityPostItem {
        val relaySummary = relayParentSummary?.let { relay ->
            CommunityRelayParentItem(
                id = relay.id,
                userNickname = relay.userNickname,
                placeTag = relay.placeTag,
                sceneType = relay.sceneType,
                imageUrl = buildAbsoluteImageUrl(serverUrl = serverUrl, imagePath = relay.imageUrl),
            )
        }
        return CommunityPostItem(
            id = id,
            userId = userId,
            userNickname = userNickname,
            feedbackId = feedbackId,
            imageUrl = buildAbsoluteImageUrl(serverUrl = serverUrl, imagePath = imageUrl),
            sceneType = sceneType,
            placeTag = placeTag,
            rating = rating.coerceIn(0, 5),
            reviewText = reviewText,
            caption = caption,
            postType = postType,
            sourceType = sourceType,
            likeCount = likeCount.coerceAtLeast(0),
            commentCount = commentCount.coerceAtLeast(0),
            likedByMe = likedByMe,
            styleTemplatePostId = styleTemplatePostId,
            relayParentSummary = relaySummary,
            createdAt = createdAt,
        )
    }

    private fun CommunityCommentDto.toCommunityComment(): CommunityCommentItem {
        return CommunityCommentItem(
            id = id,
            postId = postId,
            userId = userId,
            userNickname = userNickname,
            text = text,
            createdAt = createdAt,
            canDelete = canDelete,
        )
    }

    private fun CommunityRemakeGuideDto.toCommunityRemakeGuide(serverUrl: String): CommunityRemakeGuide {
        return CommunityRemakeGuide(
            templatePost = templatePost.toCommunityPost(serverUrl = serverUrl),
            shotScript = shotScript,
            cameraHint = cameraHint,
            implementationStatus = implementationStatus,
            placeholderNotes = placeholderNotes,
        )
    }

    private fun buildAbsoluteImageUrl(serverUrl: String, imagePath: String): String {
        val trimmed = imagePath.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val prefix = serverUrl.trimEnd('/')
        val suffix = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return prefix + suffix
    }

    fun updateServerUrl(value: String) {
        viewModelScope.launch { settingsRepository.updateServerUrl(value) }
    }

    fun updateIntervalMs(value: Long) {
        viewModelScope.launch { settingsRepository.updateIntervalMs(value) }
    }

    fun updateVoiceEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateVoiceEnabled(value) }
    }

    fun updateDebugEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateDebugEnabled(value) }
    }

    fun updateGuideProvider(provider: GuideProvider) {
        viewModelScope.launch { settingsRepository.updateGuideProvider(provider) }
    }

    fun updateCaptureMode(mode: CaptureMode) {
        sceneAutoSwitchLockedByManual = mode != CaptureMode.AUTO
        sceneFastDetectUntilMs = max(sceneFastDetectUntilMs, System.currentTimeMillis() + SCENE_SWITCH_BOOST_WINDOW_MS)
        lastSceneClassifyAt = 0L
        pendingSceneSwitch = null
        pendingSceneSwitchVotes = 0
        pendingSubjectJumpCenter = null
        pendingSubjectJumpVotes = 0
        viewModelScope.launch { settingsRepository.updateCaptureMode(mode) }
    }

    fun saveSettings(
        serverUrl: String,
        intervalMs: Long,
        voiceEnabled: Boolean,
        debugEnabled: Boolean,
        guideProvider: GuideProvider,
        captureMode: CaptureMode,
        onSaved: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            settingsRepository.updateAll(
                AppSettings(
                    serverUrl = serverUrl,
                    intervalMs = intervalMs,
                    voiceEnabled = voiceEnabled,
                    debugEnabled = debugEnabled,
                    guideProvider = guideProvider,
                    captureMode = captureMode,
                ),
            )
            logClientInfo("settings.save", "updated and persisted")
            onSaved?.invoke()
        }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepository.resetDefaults() }
    }

    override fun onCleared() {
        currentAnalyzeJob?.cancel()
        composeJobPolling?.cancel()
        cocreateJobPolling?.cancel()
        ttsSpeaker?.shutdown()
        ttsSpeaker = null
        super.onCleared()
    }

    private fun getOrCreateTtsSpeaker(): TtsSpeaker {
        val existing = ttsSpeaker
        if (existing != null) return existing
        return TtsSpeaker(getApplication()).also { ttsSpeaker = it }
    }

    private fun blendOffset(previous: Offset?, incoming: Offset?, alpha: Float): Offset? {
        if (incoming == null) return null
        val safe = alpha.coerceIn(0.1f, 0.9f)
        val base = previous ?: incoming
        return Offset(
            x = (base.x + (incoming.x - base.x) * safe).coerceIn(0f, 1f),
            y = (base.y + (incoming.y - base.y) * safe).coerceIn(0f, 1f),
        )
    }

    private fun blendRect(previous: Rect?, incoming: Rect?, alpha: Float): Rect? {
        if (incoming == null) return null
        val safe = alpha.coerceIn(0.1f, 0.9f)
        val base = previous ?: incoming
        return Rect(
            left = (base.left + (incoming.left - base.left) * safe).coerceIn(0f, 1f),
            top = (base.top + (incoming.top - base.top) * safe).coerceIn(0f, 1f),
            right = (base.right + (incoming.right - base.right) * safe).coerceIn(0f, 1f),
            bottom = (base.bottom + (incoming.bottom - base.bottom) * safe).coerceIn(0f, 1f),
        )
    }

    private fun offsetDistance(a: Offset, b: Offset): Float {
        val dx = (a.x - b.x)
        val dy = (a.y - b.y)
        return sqrt(dx * dx + dy * dy)
    }

    private fun rectArea(rect: Rect?): Float {
        if (rect == null) return 0f
        val width = (rect.right - rect.left).coerceAtLeast(0f)
        val height = (rect.bottom - rect.top).coerceAtLeast(0f)
        return width * height
    }

    private fun shouldSuppressSubjectOverlay(
        scene: SceneType,
        sceneConfidence: Float,
        bbox: Rect?,
    ): Boolean {
        val area = rectArea(bbox)
        if (bbox == null) return true
        if (area < GLOBAL_SUBJECT_MIN_AREA) return true
        return scene == SceneType.GENERAL &&
            sceneConfidence < GENERAL_SUBJECT_MIN_CONFIDENCE &&
            area < GENERAL_SUBJECT_MIN_AREA
    }

    private data class SceneDecision(
        val scene: SceneType,
        val confidence: Float,
        val acceptIncomingOverlay: Boolean,
        val switched: Boolean,
    )

    private fun buildMoveHintText(
        bbox: Rect?,
        targetPoint: Offset?,
        subjectCenter: Offset?,
    ): String {
        val target = targetPoint ?: return ""
        val subject = subjectCenter ?: bbox?.let { Offset((it.left + it.right) * 0.5f, (it.top + it.bottom) * 0.5f) }
            ?: return ""
        val dx = (target.x - subject.x).coerceIn(-1f, 1f)
        val dy = (target.y - subject.y).coerceIn(-1f, 1f)
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 0.035f) return "主体位置基本理想，可直接拍摄"

        val horiz = when {
            dx > 0.02f -> "右"
            dx < -0.02f -> "左"
            else -> ""
        }
        val vert = when {
            dy > 0.02f -> "下"
            dy < -0.02f -> "上"
            else -> ""
        }
        val direction = (vert + horiz).ifBlank { "微调" }
        val distancePct = (dist * 100f).toInt().coerceIn(1, 99)
        val angleDeg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toInt()
        return "移动建议：向${direction}约${distancePct}%（角度${angleDeg}°）"
    }

    private fun normalizeUiTipText(rawText: String): String {
        val text = rawText.trim().ifBlank { "请微调构图" }
        val overlay = _uiState.value.overlay
        val target = overlay.targetPointNorm ?: return text
        val hasDirectionWord = Regex(
            """左上|右上|左下|右下|左侧|右侧|上方|下方|向左|向右|向上|向下|上三分|下三分|左三分|右三分|左边|右边""",
        )
            .containsMatchIn(text)
        if (!hasDirectionWord) return text
        return when (overlay.grid) {
            "thirds" -> "构图目标：主体靠近三分线${thirdsCornerLabel(target)}交点，按箭头微调。"
            "center" -> "构图目标：主体靠近画面中心，按箭头微调。"
            else -> "构图目标：按箭头将主体移动到高亮目标点附近。"
        }
    }

    private fun thirdsCornerLabel(target: Offset): String {
        val x = target.x.coerceIn(0f, 1f)
        val y = target.y.coerceIn(0f, 1f)
        val horizontal = if (x < 0.5f) "左" else "右"
        val vertical = if (y < 0.5f) "上" else "下"
        return horizontal + vertical
    }

    private fun updateStableStreak(score: Float): Boolean {
        if (score >= 0.84f) {
            stableFrameStreak = min(20, stableFrameStreak + 1)
        } else if (score < 0.65f) {
            stableFrameStreak = max(0, stableFrameStreak - 2)
        } else {
            stableFrameStreak = max(0, stableFrameStreak - 1)
        }
        return stableFrameStreak >= 3
    }

    private fun estimateFrameStability(imageProxy: ImageProxy): Float {
        val yPlane = imageProxy.planes.firstOrNull() ?: return 0f
        val buffer = yPlane.buffer.duplicate()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride.coerceAtLeast(1)
        val width = imageProxy.width.coerceAtLeast(1)
        val height = imageProxy.height.coerceAtLeast(1)
        val limit = buffer.limit()
        val gridW = 8
        val gridH = 8
        val signature = FloatArray(gridW * gridH)

        var idx = 0
        for (gy in 0 until gridH) {
            val row = ((gy + 0.5f) * height / gridH).toInt().coerceIn(0, height - 1)
            for (gx in 0 until gridW) {
                val col = ((gx + 0.5f) * width / gridW).toInt().coerceIn(0, width - 1)
                val offset = row * rowStride + col * pixelStride
                val y = if (offset in 0 until limit) {
                    buffer.get(offset).toInt() and 0xFF
                } else {
                    127
                }
                signature[idx++] = y / 255f
            }
        }

        val previous = previousLumaSignature
        previousLumaSignature = signature
        if (previous == null || previous.size != signature.size) return 0f

        var diff = 0f
        for (i in signature.indices) {
            diff += abs(signature[i] - previous[i])
        }
        val meanDiff = diff / signature.size.toFloat()
        return (1f - (meanDiff / 0.06f)).coerceIn(0f, 1f)
    }
}

private data class AnalyzeFrameSnapshot(
    val bytes: ByteArray,
    val rotation: Int,
    val capturedAtMs: Long,
    val stable: Boolean,
    val stabilityScore: Float,
)
