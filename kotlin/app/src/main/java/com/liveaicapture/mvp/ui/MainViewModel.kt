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
        private const val TAG = "CamMate"
        private const val UI_STABILITY_PUBLISH_INTERVAL_MS = 320L
        private const val STABLE_SCENE_DETECT_INTERVAL_MS = 1_800L
        private const val UNSTABLE_SCENE_DETECT_INTERVAL_MS = 1_620L
        private const val MIN_UNSTABLE_SCENE_DETECT_INTERVAL_MS = 810L
        private const val SCENE_SWITCH_BOOST_WINDOW_MS = 2_200L
        private const val SCENE_SWITCH_BOOST_INTERVAL_MS = 810L
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
        sceneAutoSwitchLockedByManual = false
        pendingSceneSwitch = null
        pendingSceneSwitchVotes = 0
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
            val incomingBox = if (decision.acceptIncomingOverlay && !suppressSubject) classified.bbox else null
            val incomingCenter = if (decision.acceptIncomingOverlay && !suppressSubject) classified.center else null
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
        _uiState.update { it.copy(statusText = "反馈已提交，感谢支持", showPostCaptureChoice = false) }
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
