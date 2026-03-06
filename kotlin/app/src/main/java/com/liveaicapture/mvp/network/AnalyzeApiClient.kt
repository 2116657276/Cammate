package com.liveaicapture.mvp.network

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.liveaicapture.mvp.data.AnalyzeEvent
import com.liveaicapture.mvp.data.CaptureMode
import com.liveaicapture.mvp.data.SceneType
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnalyzeApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        serverUrl: String,
        bearerToken: String,
        imageBase64: String,
        rotationDegrees: Int,
        lensFacing: String,
        exposureCompensation: Int,
        onRawLine: (String) -> Unit,
        onEvent: (AnalyzeEvent) -> Unit,
    ) {
        return withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("image_base64", JsonPrimitive(imageBase64))
                put(
                    "client_context",
                    buildJsonObject {
                        put("rotation_degrees", JsonPrimitive(rotationDegrees))
                        put("lens_facing", JsonPrimitive(lensFacing))
                        put("exposure_compensation", JsonPrimitive(exposureCompensation))
                    },
                )
            }.toString()

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/analyze")
                .header("Authorization", "Bearer $bearerToken")
                .post(payload.toRequestBody(requestMediaType))
                .build()

            val call = httpClient.newCall(request)
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val source = body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        continue
                    }
                    onRawLine(trimmed)
                    parseLineAsEvents(trimmed).forEach(onEvent)
                }
            }
        }
    }

    private fun parseLineAsEvents(line: String): List<AnalyzeEvent> {
        return try {
            val element = json.parseToJsonElement(line)
            when (element) {
                is JsonObject -> parseObject(element)
                is JsonArray -> element.flatMap { item ->
                    if (item is JsonObject) parseObject(item) else emptyList()
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseObject(obj: JsonObject): List<AnalyzeEvent> {
        val type = obj["type"]?.jsonPrimitive?.content
        if (type != null) {
            val event = parseTypedEvent(type, obj)
            return if (event == null) emptyList() else listOf(event)
        }

        val events = mutableListOf<AnalyzeEvent>()
        obj["scene"]?.jsonObject?.let {
            parseTypedEvent(
                "scene",
                buildJsonObject {
                    put("type", JsonPrimitive("scene"))
                    it.forEach { (k, v) -> put(k, v) }
                },
            )?.let(events::add)
        }
        obj["strategy"]?.jsonObject?.let {
            parseTypedEvent(
                "strategy",
                buildJsonObject {
                    put("type", JsonPrimitive("strategy"))
                    it.forEach { (k, v) -> put(k, v) }
                },
            )?.let(events::add)
        }
        obj["target"]?.jsonObject?.let {
            parseTypedEvent(
                "target",
                buildJsonObject {
                    put("type", JsonPrimitive("target"))
                    it.forEach { (k, v) -> put(k, v) }
                },
            )?.let(events::add)
        }
        obj["ui"]?.jsonObject?.let {
            parseTypedEvent(
                "ui",
                buildJsonObject {
                    put("type", JsonPrimitive("ui"))
                    it.forEach { (k, v) -> put(k, v) }
                },
            )?.let(events::add)
        }
        obj["param"]?.jsonObject?.let {
            parseTypedEvent(
                "param",
                buildJsonObject {
                    put("type", JsonPrimitive("param"))
                    it.forEach { (k, v) -> put(k, v) }
                },
            )?.let(events::add)
        }
        if (events.none { it is AnalyzeEvent.Done }) {
            events += AnalyzeEvent.Done
        }
        return events
    }

    private fun parseTypedEvent(type: String, obj: JsonObject): AnalyzeEvent? {
        return when (type) {
            "scene" -> {
                val scene = SceneType.fromRaw(obj["scene"]?.jsonPrimitive?.content ?: "general")
                val mode = CaptureMode.fromRaw(obj["mode"]?.jsonPrimitive?.content ?: "auto")
                val confidence = (obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5).toFloat()
                AnalyzeEvent.Scene(scene = scene, mode = mode, confidence = confidence)
            }

            "strategy" -> {
                val point = parseNormPoint(obj["target_point_norm"])
                AnalyzeEvent.Strategy(
                    grid = obj["grid"]?.jsonPrimitive?.content ?: "thirds",
                    targetPoint = point,
                )
            }

            "target" -> {
                val bbox = parseNormRect(obj["bbox_norm"])
                val center = parseNormPoint(obj["center_norm"])
                AnalyzeEvent.Target(bbox = bbox, center = center)
            }

            "ui" -> AnalyzeEvent.Ui(
                text = obj["text"]?.jsonPrimitive?.content ?: "请调整构图",
                level = obj["level"]?.jsonPrimitive?.content ?: "info",
            )

            "param" -> AnalyzeEvent.Param(
                exposureCompensation = obj["exposure_compensation"]?.jsonPrimitive?.intOrNull ?: 0,
            )

            "done" -> AnalyzeEvent.Done
            else -> null
        }
    }

    private fun parseNormPoint(element: JsonElement?): Offset? {
        val arr = element?.jsonArray ?: return null
        if (arr.size < 2) return null
        val x = arr[0].jsonPrimitive.doubleOrNull ?: return null
        val y = arr[1].jsonPrimitive.doubleOrNull ?: return null
        return Offset(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f))
    }

    private fun parseNormRect(element: JsonElement?): Rect? {
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
