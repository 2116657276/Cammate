package com.liveaicapture.mvp.network

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun newRequestId(prefix: String): String {
    val shortId = UUID.randomUUID().toString().replace("-", "").take(10)
    return "${prefix}_${shortId}"
}

internal fun parseHttpError(
    json: Json,
    raw: String,
    code: Int,
    requestId: String,
): String {
    val detail = extractErrorDetail(json, raw).ifBlank { "HTTP $code" }
    return "HTTP $code req=$requestId detail=$detail"
}

private fun extractErrorDetail(json: Json, raw: String): String {
    return try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val detail = obj["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (detail.isNotBlank()) detail else raw.trim().take(160)
    } catch (_: Exception) {
        raw.trim().take(160)
    }
}
