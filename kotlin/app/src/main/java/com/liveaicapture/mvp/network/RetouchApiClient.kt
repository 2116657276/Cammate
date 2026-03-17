package com.liveaicapture.mvp.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RetouchApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(95, TimeUnit.SECONDS)
        .writeTimeout(95, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun retouch(
        serverUrl: String,
        bearerToken: String,
        imageBase64: String,
        preset: String,
        strength: Float,
        sceneHint: String,
        customPrompt: String? = null,
    ): RetouchResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("retouch")
        val payload = buildJsonObject {
            put("image_base64", JsonPrimitive(imageBase64))
            put("preset", JsonPrimitive(preset))
            put("strength", JsonPrimitive(strength))
            put("scene_hint", JsonPrimitive(sceneHint))
            customPrompt?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("custom_prompt", JsonPrimitive(it))
            }
        }.toString()

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/retouch")
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .post(payload.toRequestBody(requestMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            RetouchResult(
                imageBase64 = obj["retouched_image_base64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                requestId = responseRequestId,
            )
        }
    }
}

data class RetouchResult(
    val imageBase64: String,
    val provider: String,
    val model: String,
    val requestId: String,
)
