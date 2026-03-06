package com.liveaicapture.mvp.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FeedbackApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun submit(
        serverUrl: String,
        bearerToken: String,
        rating: Int,
        scene: String,
        tipText: String,
        photoUri: String?,
        isRetouch: Boolean,
        sessionMeta: JsonElement,
    ): Int = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("rating", JsonPrimitive(rating))
            put("scene", JsonPrimitive(scene))
            put("tip_text", JsonPrimitive(tipText))
            if (!photoUri.isNullOrBlank()) put("photo_uri", JsonPrimitive(photoUri))
            put("is_retouch", JsonPrimitive(isRetouch))
            put("session_meta", sessionMeta)
        }.toString()

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/feedback")
            .header("Authorization", "Bearer $bearerToken")
            .post(payload.toRequestBody(requestMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseError(raw, response.code))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["feedback_id"]?.jsonPrimitive?.intOrNull ?: 0
        }
    }

    private fun parseError(raw: String, code: Int): String {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["detail"]?.jsonPrimitive?.contentOrNull ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}
