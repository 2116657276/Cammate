package com.liveaicapture.mvp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "live_ai_capture_settings")

class SettingsRepository(private val context: Context) {
    private val defaultServerUrl = "http://10.0.2.2:8010"

    private fun normalizeServerUrl(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return defaultServerUrl
        if (
            value == "http://10.0.2.2:8000" ||
            value == "http://127.0.0.1:8000" ||
            value == "http://localhost:8000"
        ) {
            return when {
                value.contains("10.0.2.2") -> "http://10.0.2.2:8010"
                value.contains("127.0.0.1") -> "http://127.0.0.1:8010"
                else -> "http://localhost:8010"
            }
        }
        return value
    }

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val intervalMs = longPreferencesKey("interval_ms")
        val voiceEnabled = booleanPreferencesKey("voice_enabled")
        val debugEnabled = booleanPreferencesKey("debug_enabled")
        val guideProvider = stringPreferencesKey("guide_provider")
        val captureMode = stringPreferencesKey("capture_mode")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { pref ->
        AppSettings(
            serverUrl = normalizeServerUrl(pref[Keys.serverUrl]),
            intervalMs = (pref[Keys.intervalMs] ?: 1000L).coerceIn(300L, 5000L),
            voiceEnabled = pref[Keys.voiceEnabled] ?: true,
            debugEnabled = pref[Keys.debugEnabled] ?: false,
            guideProvider = GuideProvider.fromRaw(pref[Keys.guideProvider] ?: GuideProvider.CLOUD.raw),
            captureMode = CaptureMode.fromRaw(pref[Keys.captureMode] ?: CaptureMode.AUTO.raw),
        )
    }

    suspend fun updateServerUrl(value: String) {
        context.dataStore.edit { it[Keys.serverUrl] = normalizeServerUrl(value) }
    }

    suspend fun updateIntervalMs(value: Long) {
        context.dataStore.edit { it[Keys.intervalMs] = value.coerceIn(300L, 5000L) }
    }

    suspend fun updateVoiceEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.voiceEnabled] = value }
    }

    suspend fun updateDebugEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.debugEnabled] = value }
    }

    suspend fun updateGuideProvider(provider: GuideProvider) {
        context.dataStore.edit { it[Keys.guideProvider] = provider.raw }
    }

    suspend fun updateCaptureMode(mode: CaptureMode) {
        context.dataStore.edit { it[Keys.captureMode] = mode.raw }
    }

    suspend fun updateAll(settings: AppSettings) {
        context.dataStore.edit { pref ->
            pref[Keys.serverUrl] = normalizeServerUrl(settings.serverUrl)
            pref[Keys.intervalMs] = settings.intervalMs.coerceIn(300L, 5000L)
            pref[Keys.voiceEnabled] = settings.voiceEnabled
            pref[Keys.debugEnabled] = settings.debugEnabled
            pref[Keys.guideProvider] = settings.guideProvider.raw
            pref[Keys.captureMode] = settings.captureMode.raw
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { pref ->
            pref[Keys.serverUrl] = defaultServerUrl
            pref[Keys.intervalMs] = 1000L
            pref[Keys.voiceEnabled] = true
            pref[Keys.debugEnabled] = false
            pref[Keys.guideProvider] = GuideProvider.CLOUD.raw
            pref[Keys.captureMode] = CaptureMode.AUTO.raw
        }
    }
}
