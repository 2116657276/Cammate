package com.liveaicapture.mvp.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CommunityApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun publishPost(
        serverUrl: String,
        bearerToken: String,
        feedbackId: Int,
        imageBase64: String,
        placeTag: String,
        sceneType: String,
    ): CommunityPostDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_publish")
        val payload = buildJsonObject {
            put("feedback_id", JsonPrimitive(feedbackId))
            put("image_base64", JsonPrimitive(imageBase64))
            put("place_tag", JsonPrimitive(placeTag.trim()))
            put("scene_type", JsonPrimitive(sceneType.trim().lowercase()))
        }.toString()

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/community/posts")
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
            parsePost(raw)
        }
    }

    suspend fun fetchFeed(
        serverUrl: String,
        bearerToken: String,
        offset: Int = 0,
        limit: Int = 20,
    ): CommunityFeedResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_feed")
        val base = "${serverUrl.trimEnd('/')}/community/feed".toHttpUrlOrNull()
            ?: throw IOException("invalid server url")
        val url = base.newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val items = obj["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                parsePost(item.toString())
            }
            val nextOffset = obj["next_offset"]?.jsonPrimitive?.intOrNull ?: (offset + items.size)
            CommunityFeedResult(items = items, nextOffset = nextOffset)
        }
    }

    suspend fun fetchRecommendations(
        serverUrl: String,
        bearerToken: String,
        placeTag: String,
        sceneType: String,
        limit: Int = 12,
    ): List<CommunityRecommendationDto> = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_reco")
        val base = "${serverUrl.trimEnd('/')}/community/recommendations".toHttpUrlOrNull()
            ?: throw IOException("invalid server url")
        val builder = base.newBuilder().addQueryParameter("limit", limit.toString())
        placeTag.trim().takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("place_tag", it) }
        sceneType.trim().takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("scene_type", it) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                val itemObj = item.jsonObject
                val postObj = itemObj["post"]?.jsonObject ?: return@mapNotNull null
                CommunityRecommendationDto(
                    post = parsePost(postObj.toString()),
                    score = (itemObj["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toFloat(),
                    reason = itemObj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }
    }

    suspend fun compose(
        serverUrl: String,
        bearerToken: String,
        referencePostId: Int,
        personImageBase64: String,
        strength: Float,
    ): CommunityComposeResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_compose")
        val payload = buildJsonObject {
            put("reference_post_id", JsonPrimitive(referencePostId))
            put("person_image_base64", JsonPrimitive(personImageBase64))
            put("strength", JsonPrimitive(strength.coerceIn(0f, 1f)))
        }.toString()
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/community/compose")
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
            CommunityComposeResult(
                imageBase64 = obj["composed_image_base64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                requestId = responseRequestId,
            )
        }
    }

    private fun parsePost(raw: String): CommunityPostDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityPostDto(
            id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
            userId = obj["user_id"]?.jsonPrimitive?.intOrNull ?: 0,
            userNickname = obj["user_nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            feedbackId = obj["feedback_id"]?.jsonPrimitive?.intOrNull ?: 0,
            imageUrl = obj["image_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sceneType = obj["scene_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            placeTag = obj["place_tag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            rating = obj["rating"]?.jsonPrimitive?.intOrNull ?: 0,
            reviewText = obj["review_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = obj["created_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
        )
    }
}

data class CommunityPostDto(
    val id: Int,
    val userId: Int,
    val userNickname: String,
    val feedbackId: Int,
    val imageUrl: String,
    val sceneType: String,
    val placeTag: String,
    val rating: Int,
    val reviewText: String,
    val createdAt: Long,
)

data class CommunityRecommendationDto(
    val post: CommunityPostDto,
    val score: Float,
    val reason: String,
)

data class CommunityFeedResult(
    val items: List<CommunityPostDto>,
    val nextOffset: Int,
)

data class CommunityComposeResult(
    val imageBase64: String,
    val provider: String,
    val model: String,
    val requestId: String,
)
