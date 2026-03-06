package com.liveaicapture.mvp.ui

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liveaicapture.mvp.camera.FrameEncoder
import com.liveaicapture.mvp.data.AnalyzeEvent
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
import com.liveaicapture.mvp.guide.FrameSceneClassifier
import com.liveaicapture.mvp.guide.LocalGuideEngine
import com.liveaicapture.mvp.network.AnalyzeApiClient
import com.liveaicapture.mvp.network.AuthApiClient
import com.liveaicapture.mvp.network.FeedbackApiClient
import com.liveaicapture.mvp.network.RetouchApiClient
import com.liveaicapture.mvp.tts.TtsSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val settingsRepository = SettingsRepository(application)
    private val sessionRepository = SessionRepository(application)
    private val localGuideEngine = LocalGuideEngine()
    private val frameSceneClassifier = FrameSceneClassifier()
    private val analyzeApiClient = AnalyzeApiClient()
    private val authApiClient = AuthApiClient()
    private val retouchApiClient = RetouchApiClient()
    private val feedbackApiClient = FeedbackApiClient()
    private val ttsSpeaker = TtsSpeaker(application)

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
    private var currentAnalyzeJob: Job? = null
    private var currentExposureCompensation = 0

    private val frameLock = Any()
    private var latestFrameJpeg: ByteArray? = null
    private var latestFrameRotation = 0
    private var lastSceneClassifyAt = 0L

    private var bearerToken: String = ""

    private val genericFallbackTips = listOf(
        "连接异常，先用本地建议：保持水平，让主体靠近三分点。",
        "网络不可用，先用通用构图：主体略偏一侧并留出前方空间。",
        "云端暂不可达，先用本地建议：简化背景并突出主体轮廓。",
        "服务连接失败，建议先锁定主光区，再微调取景位置。",
        "已切换本地引导：先稳住机身，再调整构图层次。",
    )

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
            } catch (_: Exception) {
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
            } catch (e: Exception) {
                _authUiState.update {
                    it.copy(
                        loading = false,
                        authenticated = false,
                        user = null,
                        errorMessage = e.message ?: "登录失败",
                    )
                }
            }
        }
    }

    fun register(email: String, password: String, nickname: String) {
        viewModelScope.launch {
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
            } catch (e: Exception) {
                _authUiState.update {
                    it.copy(
                        loading = false,
                        authenticated = false,
                        user = null,
                        errorMessage = e.message ?: "注册失败",
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            val token = bearerToken
            if (token.isNotBlank()) {
                try {
                    authApiClient.logout(_uiState.value.settings.serverUrl, token)
                } catch (_: Exception) {
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
        }
    }

    private fun resetFlowAfterLogout() {
        _retouchUiState.value = RetouchUiState()
        _feedbackUiState.value = FeedbackUiState()
        _uiState.update {
            it.copy(
                aiEnabled = true,
                tipText = "点击 AI分析 获取构图建议",
                statusText = "请登录后开始拍摄",
                overlay = it.overlay.copy(
                    grid = "thirds",
                    targetPointNorm = Offset(0.5f, 0.5f),
                    bboxNorm = null,
                ),
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

        val frameAspectRatio = computeRotatedAspectRatio(
            width = imageProxy.width,
            height = imageProxy.height,
            rotationDegrees = rotationDegrees,
        )
        _uiState.update {
            it.copy(overlay = it.overlay.copy(sourceAspectRatio = frameAspectRatio))
        }

        val jpegBytes = try {
            FrameEncoder.imageProxyToJpegBytes(imageProxy)
        } catch (_: Exception) {
            _uiState.update { it.copy(statusText = "帧编码失败") }
            imageProxy.close()
            return
        }
        imageProxy.close()

        synchronized(frameLock) {
            latestFrameJpeg = jpegBytes
            latestFrameRotation = rotationDegrees
        }

        if (!snapshot.aiEnabled) return

        val now = System.currentTimeMillis()
        val classifyInterval = (snapshot.settings.intervalMs / 2L).coerceAtLeast(450L)
        if (now - lastSceneClassifyAt < classifyInterval) return
        lastSceneClassifyAt = now

        try {
            val classified = frameSceneClassifier.classify(jpegBytes)
            val finalScene = resolveSceneByMode(
                classifiedScene = classified.scene,
                mode = snapshot.settings.captureMode,
            )
            val finalConfidence = if (snapshot.settings.captureMode == CaptureMode.AUTO) {
                classified.confidence
            } else {
                0.95f
            }
            applyEvent(
                AnalyzeEvent.Scene(
                    scene = finalScene,
                    mode = snapshot.settings.captureMode,
                    confidence = finalConfidence,
                ),
            )
            _uiState.update { it.copy(sessionFrameCount = it.sessionFrameCount + 1) }
        } catch (_: Exception) {
            _uiState.update { it.copy(statusText = "场景识别失败") }
        }
    }

    fun requestAiAnalyze() {
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _uiState.update { it.copy(statusText = "请先登录") }
            return
        }

        val frameBytes = synchronized(frameLock) { latestFrameJpeg?.copyOf() }
        val rotation = synchronized(frameLock) { latestFrameRotation }

        if (frameBytes == null) {
            _uiState.update { it.copy(statusText = "请先稳定取景") }
            return
        }

        if (!tryAcquireAnalyzeRequest()) {
            _uiState.update { it.copy(statusText = "AI分析进行中") }
            return
        }

        _uiState.update { it.copy(analyzingTips = true, statusText = "AI分析中...") }

        val fallbackScene = _uiState.value.detectedScene
        currentAnalyzeJob?.cancel()
        currentAnalyzeJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            try {
                val activeSettings = _uiState.value.settings
                if (activeSettings.guideProvider == GuideProvider.CLOUD) {
                    runCloudGuidanceWithFallback(
                        settings = activeSettings,
                        jpegBytes = frameBytes,
                        rotationDegrees = rotation,
                        fallbackScene = fallbackScene,
                        nowMs = now,
                    )
                } else {
                    runLocalGuidance(
                        sceneType = fallbackScene,
                        nowMs = now,
                        debugPrefix = "local",
                    )
                }
            } catch (_: Exception) {
                applyGenericFallback(
                    status = "分析失败，已切换本地建议",
                    scene = fallbackScene,
                    nowMs = now,
                )
            } finally {
                releaseAnalyzeRequest()
                _uiState.update {
                    it.copy(
                        analyzingTips = false,
                        statusText = statusByProvider(it.settings.guideProvider, it.aiEnabled),
                    )
                }
            }
        }
    }

    private suspend fun runCloudGuidanceWithFallback(
        settings: com.liveaicapture.mvp.data.AppSettings,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        fallbackScene: SceneType,
        nowMs: Long,
    ) {
        val imageBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        try {
            var receivedEvent = false
            analyzeApiClient.analyze(
                serverUrl = settings.serverUrl,
                bearerToken = bearerToken,
                imageBase64 = imageBase64,
                rotationDegrees = rotationDegrees,
                lensFacing = "back",
                exposureCompensation = currentExposureCompensation,
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
                    receivedEvent = true
                    applyEvent(event)
                },
            )
            if (!receivedEvent) {
                applyGenericFallback(
                    status = "云端解析失败，已回退本地",
                    scene = fallbackScene,
                    nowMs = nowMs,
                )
            }
        } catch (_: Exception) {
            applyGenericFallback(
                status = "云端连接失败，已回退本地",
                scene = fallbackScene,
                nowMs = nowMs,
            )
        }
    }

    private fun runLocalGuidance(sceneType: SceneType, nowMs: Long, debugPrefix: String) {
        val localResult = localGuideEngine.analyze(
            sceneType = sceneType,
            nowMs = nowMs,
            currentExposureCompensation = currentExposureCompensation,
        )
        if (_uiState.value.settings.debugEnabled) {
            _uiState.update {
                it.copy(debugRaw = "{\"source\":\"$debugPrefix\"}\n${localResult.debugLine}")
            }
        }
        localResult.events.forEach(::applyEvent)
    }

    private fun applyGenericFallback(status: String, scene: SceneType, nowMs: Long) {
        val fallbackResult = localGuideEngine.analyze(
            sceneType = scene,
            nowMs = nowMs,
            currentExposureCompensation = currentExposureCompensation,
        )
        val tip = genericFallbackTips[((nowMs / 3000L) % genericFallbackTips.size).toInt()]
        fallbackResult.events.forEach { event ->
            when (event) {
                is AnalyzeEvent.Ui -> applyEvent(AnalyzeEvent.Ui(text = tip, level = "warn"))
                else -> applyEvent(event)
            }
        }
        _uiState.update { it.copy(statusText = status) }
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
                    it.copy(
                        overlay = it.overlay.copy(
                            grid = event.grid,
                            targetPointNorm = event.targetPoint,
                        ),
                    )
                }
            }

            is AnalyzeEvent.Target -> {
                _uiState.update {
                    it.copy(
                        overlay = it.overlay.copy(
                            bboxNorm = event.bbox,
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

    private fun resolveSceneByMode(classifiedScene: SceneType, mode: CaptureMode): SceneType {
        return when (mode) {
            CaptureMode.AUTO -> classifiedScene
            CaptureMode.PORTRAIT -> SceneType.PORTRAIT
            CaptureMode.GENERAL -> if (classifiedScene == SceneType.PORTRAIT) SceneType.GENERAL else classifiedScene
        }
    }

    private fun tryAcquireAnalyzeRequest(): Boolean {
        synchronized(requestGate) {
            if (analyzeInFlight) return false
            analyzeInFlight = true
            return true
        }
    }

    private fun releaseAnalyzeRequest() {
        synchronized(requestGate) {
            analyzeInFlight = false
        }
    }

    fun onPhotoCaptured(savedUri: String?) {
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
        val snapshot = _retouchUiState.value
        val originalUri = snapshot.originalPhotoUri
        if (originalUri.isNullOrBlank()) {
            _retouchUiState.update { it.copy(errorMessage = "缺少原始照片") }
            return
        }
        if (!_authUiState.value.authenticated || bearerToken.isBlank()) {
            _retouchUiState.update { it.copy(errorMessage = "请先登录") }
            return
        }

        viewModelScope.launch {
            _retouchUiState.update { it.copy(applying = true, errorMessage = null) }
            try {
                val imageBase64 = readUriAsBase64(originalUri)
                val result = retouchApiClient.retouch(
                    serverUrl = _uiState.value.settings.serverUrl,
                    bearerToken = bearerToken,
                    imageBase64 = imageBase64,
                    preset = snapshot.preset.raw,
                    strength = snapshot.strength,
                    sceneHint = snapshot.sceneHint.raw,
                )
                if (result.imageBase64.isBlank()) {
                    throw IllegalStateException("云端没有返回图片")
                }
                _retouchUiState.update {
                    it.copy(
                        applying = false,
                        previewBase64 = result.imageBase64,
                        provider = result.provider,
                        model = result.model,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                _retouchUiState.update {
                    it.copy(
                        applying = false,
                        errorMessage = e.message ?: "修图失败，请重试",
                    )
                }
            }
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
            } catch (_: Exception) {
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
            } catch (e: Exception) {
                _feedbackUiState.update {
                    it.copy(
                        submitting = false,
                        submitted = false,
                        errorMessage = e.message ?: "反馈提交失败",
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

    private fun readUriAsBase64(uriString: String): String {
        val resolver = getApplication<Application>().contentResolver
        val uri = android.net.Uri.parse(uriString)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("图片读取失败")
        if (bytes.isEmpty()) throw IllegalStateException("图片为空")
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
        return when (provider) {
            GuideProvider.LOCAL -> "本地识别运行中"
            GuideProvider.CLOUD -> "云端分析可用"
        }
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

    fun resetSettings() {
        viewModelScope.launch { settingsRepository.resetDefaults() }
    }

    override fun onCleared() {
        currentAnalyzeJob?.cancel()
        ttsSpeaker.shutdown()
        super.onCleared()
    }
}
