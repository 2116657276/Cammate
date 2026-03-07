package com.liveaicapture.mvp.ui

import android.app.Application
import android.content.ContentValues
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
import com.liveaicapture.mvp.data.FeedbackUiState
import com.liveaicapture.mvp.data.GuideProvider
import com.liveaicapture.mvp.data.RetouchPreset
import com.liveaicapture.mvp.data.RetouchUiState
import com.liveaicapture.mvp.data.SceneType
import com.liveaicapture.mvp.data.SessionRepository
import com.liveaicapture.mvp.data.SettingsRepository
import com.liveaicapture.mvp.log.AppLog
import com.liveaicapture.mvp.network.AnalyzeApiClient
import com.liveaicapture.mvp.network.AuthApiClient
import com.liveaicapture.mvp.network.FeedbackApiClient
import com.liveaicapture.mvp.network.SceneApiClient
import com.liveaicapture.mvp.tts.TtsSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Job
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
        private const val TAG = "LiveAICapture"
        private const val UI_STABILITY_PUBLISH_INTERVAL_MS = 320L
        private const val STABLE_SCENE_DETECT_INTERVAL_MS = 12_000L
        private const val UNSTABLE_SCENE_DETECT_INTERVAL_MS = 2_500L
        private const val MIN_UNSTABLE_SCENE_DETECT_INTERVAL_MS = 1_800L
    }

    private val settingsRepository = SettingsRepository(application)
    private val sessionRepository = SessionRepository(application)
    private val analyzeApiClient = AnalyzeApiClient()
    private val sceneApiClient = SceneApiClient()
    private val authApiClient = AuthApiClient()
    private val feedbackApiClient = FeedbackApiClient()
    private val ttsSpeaker = TtsSpeaker(application)
    private val appLogger = AppLog.get(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _authUiState = MutableStateFlow(AuthUiState())
    val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

    private val _retouchUiState = MutableStateFlow(RetouchUiState())
    val retouchUiState: StateFlow<RetouchUiState> = _retouchUiState.asStateFlow()

    private val _feedbackUiState = MutableStateFlow(FeedbackUiState())
    val feedbackUiState: StateFlow<FeedbackUiState> = _feedbackUiState.asStateFlow()

    private val requestGate = Any()
    private var analyzeInFlight = false
    private var sceneDetectInFlight = false
    private var currentAnalyzeJob: Job? = null
    private var currentExposureCompensation = 0

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
                _authUiState.value = AuthUiState(
                    checkingSession = false,
                    authenticated = false,
                    user = null,
                    errorMessage = null,
                )
                return@launch
            }

            bearerToken = session.bearerToken
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
                        errorMessage = "登录失败，请检查账号或网络后重试",
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
                        errorMessage = "注册失败，请检查信息后重试",
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            logClientInfo("auth.logout", "start")
            val token = bearerToken
            if (token.isNotBlank()) {
                try {
                    authApiClient.logout(_uiState.value.settings.serverUrl, token)
                } catch (e: Exception) {
                    logClientError(
                        scope = "auth.logout",
                        throwable = e,
                        userHint = "登出接口调用失败",
                    )
                }
            }
            sessionRepository.clearSession()
            bearerToken = ""
            _authUiState.value = AuthUiState(
                checkingSession = false,
                authenticated = false,
                loading = false,
                user = null,
                errorMessage = null,
            )
            resetFlowAfterLogout()
            logClientInfo("auth.logout", "completed")
        }
    }

    private fun resetFlowAfterLogout() {
        _retouchUiState.value = RetouchUiState()
        _feedbackUiState.value = FeedbackUiState()
        lastSuccessfulTipText = ""
        recentSuccessfulTips.clear()
        _uiState.update {
            it.copy(
                aiEnabled = true,
                tipText = "点击 AI分析 获取构图建议",
                statusText = "请登录后开始拍摄",
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

    fun onFrame(imageProxy: ImageProxy) {
        val snapshot = _uiState.value
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val now = System.currentTimeMillis()

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
        val classifyInterval = if (isStable) {
            STABLE_SCENE_DETECT_INTERVAL_MS
        } else {
            unstableDetectInterval
        }
        val shouldClassify = now - lastSceneClassifyAt >= classifyInterval
        val shouldRefreshAnalyzeFrame = isStable && (now - lastStableFrameAtMs >= 1200L)
        val shouldEncodeFrame = shouldClassify || shouldRefreshAnalyzeFrame || latestFrameJpeg == null
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

        if (!shouldClassify) return
        if (_uiState.value.analyzingTips) return
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) return
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
                    captureMode = mode.raw,
                    sceneHint = sceneHint,
                )
                applyEvent(
                    AnalyzeEvent.Scene(
                        scene = classified.scene,
                        mode = mode,
                        confidence = classified.confidence,
                    ),
                )
                _uiState.update { state ->
                    val blendedBox = blendRect(
                        previous = state.overlay.bboxNorm,
                        incoming = classified.bbox,
                        alpha = 0.28f,
                    )
                    val blendedCenter = blendOffset(
                        previous = state.overlay.subjectCenterNorm,
                        incoming = classified.center,
                        alpha = 0.20f,
                    )
                    val overlay = state.overlay.copy(
                        bboxNorm = blendedBox ?: state.overlay.bboxNorm,
                        subjectCenterNorm = blendedCenter ?: state.overlay.subjectCenterNorm,
                    )
                    state.copy(
                        overlay = overlay,
                        moveHintText = buildMoveHintText(
                            bbox = overlay.bboxNorm,
                            targetPoint = overlay.targetPointNorm,
                            subjectCenter = overlay.subjectCenterNorm,
                        ),
                    )
                }
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
                lensFacing = "back",
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
                    it.copy(
                        detectedScene = event.scene,
                        sceneConfidence = event.confidence.coerceIn(0f, 1f),
                    )
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
                _uiState.update {
                    it.copy(
                        tipText = event.text,
                        tipLevel = event.level,
                        sessionTipCount = it.sessionTipCount + 1,
                    )
                }
                if (event.level == "info") {
                    val tip = event.text.trim()
                    lastSuccessfulTipText = tip
                    if (tip.isNotEmpty()) {
                        recentSuccessfulTips.remove(tip)
                        recentSuccessfulTips.addLast(tip)
                        while (recentSuccessfulTips.size > 4) {
                            recentSuccessfulTips.removeFirst()
                        }
                    }
                } else {
                    val lowered = event.text.lowercase()
                    if (lowered.contains("限流") || lowered.contains("冷却")) {
                        nextAnalyzeAllowedAtMs = max(nextAnalyzeAllowedAtMs, System.currentTimeMillis() + 10_000L)
                    }
                }
                ttsSpeaker.speak(event.text, _uiState.value.settings.voiceEnabled)
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
                statusText = "照片已保存",
            )
        }
        _retouchUiState.value = RetouchUiState(
            originalPhotoUri = savedUri,
            sceneHint = _uiState.value.detectedScene,
        )
        _feedbackUiState.value = FeedbackUiState()
    }

    fun updateRetouchPreset(preset: RetouchPreset) {
        _retouchUiState.update { it.copy(preset = preset, errorMessage = null) }
    }

    fun updateRetouchStrength(strength: Float) {
        _retouchUiState.update { it.copy(strength = strength.coerceIn(0f, 1f), errorMessage = null) }
    }

    fun applyRetouch() {
        logClientInfo("retouch.apply", "skipped feature_not_ready")
        _retouchUiState.update {
            it.copy(
                applying = false,
                errorMessage = "AI修图功能暂未开放，请先使用原图继续",
            )
        }
    }

    fun continueWithOriginalPhoto() {
        val sourceUri = _retouchUiState.value.originalPhotoUri ?: return
        _feedbackUiState.value = FeedbackUiState(
            visible = true,
            photoUri = sourceUri,
            isRetouched = false,
            scene = _uiState.value.detectedScene,
            tipText = _uiState.value.tipText,
        )
    }

    fun continueWithRetouchedPhoto() {
        val retouched = _retouchUiState.value.previewBase64
        if (retouched.isNullOrBlank()) {
            continueWithOriginalPhoto()
            return
        }

        viewModelScope.launch {
            val savedUri = try {
                saveBase64ToGallery(retouched, "LiveAICapture_Retouch")
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
            )
        }
    }

    fun updateFeedbackRating(value: Int) {
        _feedbackUiState.update {
            it.copy(rating = value.coerceIn(1, 5), errorMessage = null)
        }
    }

    fun submitFeedback() {
        val current = _feedbackUiState.value
        if (!current.visible || current.submitting) return
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _feedbackUiState.update { it.copy(errorMessage = "请先登录") }
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
                    sessionMeta = buildJsonObject {
                        put("frame_count", JsonPrimitive(_uiState.value.sessionFrameCount))
                        put("tip_count", JsonPrimitive(_uiState.value.sessionTipCount))
                        put("photo_count", JsonPrimitive(_uiState.value.photoCount))
                        put("capture_mode", JsonPrimitive(_uiState.value.settings.captureMode.raw))
                        put("guide_provider", JsonPrimitive(_uiState.value.settings.guideProvider.raw))
                        put("retouch_preset", JsonPrimitive(_retouchUiState.value.preset.raw))
                    },
                )

                if (feedbackId <= 0) throw IllegalStateException("反馈提交失败")
                _feedbackUiState.update {
                    it.copy(
                        submitting = false,
                        submitted = true,
                        errorMessage = null,
                    )
                }
                logClientInfo("feedback.submit", "success feedbackId=$feedbackId")
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
        _uiState.update { it.copy(statusText = "反馈已提交，感谢支持") }
    }

    private fun saveBase64ToGallery(base64Data: String, prefix: String): String? {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        if (bytes.isEmpty()) return null

        val app = getApplication<Application>()
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_$fileName")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LiveAICapture")
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
        ttsSpeaker.shutdown()
        super.onCleared()
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
