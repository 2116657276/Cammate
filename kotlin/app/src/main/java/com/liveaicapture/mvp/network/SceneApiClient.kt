package com.liveaicapture.mvp.network

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.liveaicapture.mvp.data.SceneType
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SceneApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun detect(
        serverUrl: String,
        bearerToken: String,
        imageBase64: String,
        rotationDegrees: Int,
        lensFacing: String,
        captureMode: String,
        sceneHint: String,
    ): SceneDetectResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("scene")
        val payload = buildJsonObject {
            put("image_base64", JsonPrimitive(imageBase64))
            put(
                "client_context",
                buildJsonObject {
                    put("rotation_degrees", JsonPrimitive(rotationDegrees))
                    put("lens_facing", JsonPrimitive(if (lensFacing == "front") "front" else "back"))
                    put("capture_mode", JsonPrimitive(captureMode))
                    put("scene_hint", JsonPrimitive(sceneHint))
                },
            )
        }.toString()

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/scene/detect")
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
            val scene = SceneType.fromRaw(obj["scene"]?.jsonPrimitive?.contentOrNull ?: "general")
            val confidence = (obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5).toFloat()
            val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "auto"
            val center = parseNormPoint(obj["center_norm"])
            val bbox = parseNormRect(obj["bbox_norm"])
            SceneDetectResult(
                scene = scene,
                confidence = confidence.coerceIn(0f, 1f),
                mode = mode,
                center = center,
                bbox = bbox,
            )
        }
    }

    private fun parseNormPoint(element: kotlinx.serialization.json.JsonElement?): Offset? {
        val arr = element?.jsonArray ?: return null
        if (arr.size < 2) return null
        val x = arr[0].jsonPrimitive.doubleOrNull ?: return null
        val y = arr[1].jsonPrimitive.doubleOrNull ?: return null
        return Offset(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f))
    }

    private fun parseNormRect(element: kotlinx.serialization.json.JsonElement?): Rect? {
        val arr = element?.jsonArray ?: return null
        if (arr.size < 4) return null
        val x1 = arr[0].jsonPrimitive.doubleOrNull ?: return null
        val y1 = arr[1].jsonPrimitive.doubleOrNull ?: return null
        val x2 = arr[2].jsonPrimitive.doubleOrNull ?: return null
        val y2 = arr[3].jsonPrimitive.doubleOrNull ?: return null
        val left = minOf(x1, x2).toFloat().coerceIn(0f, 1f)
        val top = minOf(y1, y2).toFloat().coerceIn(0f, 1f)
        val right = maxOf(x1, x2).toFloat().coerceIn(0f, 1f)
        val bottom = maxOf(y1, y2).toFloat().coerceIn(0f, 1f)
        return Rect(left, top, right, bottom)
    }
}

data class SceneDetectResult(
    val scene: SceneType,
    val confidence: Float,
    val mode: String,
    val center: Offset?,
    val bbox: Rect?,
)
