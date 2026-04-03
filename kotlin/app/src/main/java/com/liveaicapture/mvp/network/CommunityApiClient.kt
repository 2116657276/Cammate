package com.liveaicapture.mvp.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

class CommunityApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun publishPost(
        serverUrl: String,
        bearerToken: String,
        feedbackId: Int,
        imageBase64: String,
        placeTag: String,
        sceneType: String?,
    ): CommunityPostDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_publish_feedback")
        val payload = buildJsonObject {
            put("feedback_id", JsonPrimitive(feedbackId))
            put("image_base64", JsonPrimitive(imageBase64))
            put("place_tag", JsonPrimitive(placeTag.trim()))
            sceneType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
                put("scene_type", JsonPrimitive(it))
            }
        }.toString()
        postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/posts",
            payload = payload,
        ).let(::parsePost)
    }

    suspend fun publishDirectPost(
        serverUrl: String,
        bearerToken: String,
        imageBase64: String,
        placeTag: String,
        sceneType: String?,
        caption: String,
        reviewText: String,
        rating: Int?,
        postType: String,
        relayParentPostId: Int?,
        styleTemplatePostId: Int?,
    ): CommunityPostDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_publish_direct")
        val payload = buildJsonObject {
            put("image_base64", JsonPrimitive(imageBase64))
            put("place_tag", JsonPrimitive(placeTag.trim()))
            sceneType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
                put("scene_type", JsonPrimitive(it))
            }
            put("caption", JsonPrimitive(caption.take(280)))
            put("review_text", JsonPrimitive(reviewText.take(280)))
            rating?.let { put("rating", JsonPrimitive(it.coerceIn(1, 5))) }
            put("post_type", JsonPrimitive(postType.trim().lowercase()))
            relayParentPostId?.let { put("relay_parent_post_id", JsonPrimitive(it)) }
            styleTemplatePostId?.let { put("style_template_post_id", JsonPrimitive(it)) }
        }.toString()
        postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/posts/direct",
            payload = payload,
        ).let(::parsePost)
    }

    suspend fun publishRelayPost(
        serverUrl: String,
        bearerToken: String,
        imageBase64: String,
        placeTag: String,
        sceneType: String?,
        caption: String,
        reviewText: String,
        rating: Int?,
        relayParentPostId: Int,
        styleTemplatePostId: Int?,
    ): CommunityPostDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_publish_relay")
        val payload = buildJsonObject {
            put("image_base64", JsonPrimitive(imageBase64))
            put("place_tag", JsonPrimitive(placeTag.trim()))
            sceneType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
                put("scene_type", JsonPrimitive(it))
            }
            put("caption", JsonPrimitive(caption.take(280)))
            put("review_text", JsonPrimitive(reviewText.take(280)))
            rating?.let { put("rating", JsonPrimitive(it.coerceIn(1, 5))) }
            put("relay_parent_post_id", JsonPrimitive(relayParentPostId))
            styleTemplatePostId?.let { put("style_template_post_id", JsonPrimitive(it)) }
        }.toString()
        postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/relay/posts",
            payload = payload,
        ).let(::parsePost)
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
            val items = parsePostsArray(obj["items"]?.jsonArray ?: JsonArray(emptyList()))
            val nextOffset = obj["next_offset"]?.jsonPrimitive?.intOrNull ?: (offset + items.size)
            val hasMore = obj["has_more"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj["has_more"]?.jsonPrimitive?.toString() == "true"
            CommunityFeedResult(items = items, nextOffset = nextOffset, hasMore = hasMore)
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

    suspend fun likePost(
        serverUrl: String,
        bearerToken: String,
        postId: Int,
    ): CommunityLikeResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_like")
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/posts/$postId/likes",
            payload = "{}",
        )
        parseLike(raw)
    }

    suspend fun unlikePost(
        serverUrl: String,
        bearerToken: String,
        postId: Int,
    ): CommunityLikeResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_unlike")
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/community/posts/$postId/likes")
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .delete()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            parseLike(raw)
        }
    }

    suspend fun fetchComments(
        serverUrl: String,
        bearerToken: String,
        postId: Int,
        offset: Int = 0,
        limit: Int = 80,
    ): List<CommunityCommentDto> = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_comments")
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 80)
        val base = "${serverUrl.trimEnd('/')}/community/posts/$postId/comments".toHttpUrlOrNull()
            ?: throw IOException("invalid server url")
        val url = base.newBuilder()
            .addQueryParameter("offset", safeOffset.toString())
            .addQueryParameter("limit", safeLimit.toString())
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
            parseCommentsArray(obj["items"]?.jsonArray ?: JsonArray(emptyList()))
        }
    }

    suspend fun addComment(
        serverUrl: String,
        bearerToken: String,
        postId: Int,
        text: String,
    ): CommunityCommentDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_add_comment")
        val payload = buildJsonObject { put("text", JsonPrimitive(text.take(280))) }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/posts/$postId/comments",
            payload = payload,
        )
        parseComment(raw)
    }

    suspend fun deleteComment(
        serverUrl: String,
        bearerToken: String,
        commentId: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_delete_comment")
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/community/comments/$commentId")
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .delete()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val okRaw = obj["ok"]?.jsonPrimitive?.contentOrNull
                ?: obj["ok"]?.jsonPrimitive?.toString().orEmpty()
            okRaw == "true" || okRaw == "1"
        }
    }

    suspend fun fetchRemakeGuide(
        serverUrl: String,
        bearerToken: String,
        templatePostId: Int,
    ): CommunityRemakeGuideDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_remake_guide")
        val payload = buildJsonObject { put("template_post_id", JsonPrimitive(templatePostId)) }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/remake/guide",
            payload = payload,
        )
        parseRemakeGuide(raw)
    }

    suspend fun analyzeRemake(
        serverUrl: String,
        bearerToken: String,
        templatePostId: Int,
        candidateImageBase64: String,
    ): CommunityRemakeAnalysisDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_remake_analyze")
        val payload = buildJsonObject {
            put("template_post_id", JsonPrimitive(templatePostId))
            put("candidate_image_base64", JsonPrimitive(candidateImageBase64))
        }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/remake/analyze",
            payload = payload,
        )
        parseRemakeAnalysis(raw)
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
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/compose",
            payload = payload,
        )
        parseCompose(raw = raw, requestId = requestId)
    }

    suspend fun createComposeJob(
        serverUrl: String,
        bearerToken: String,
        referencePostId: Int,
        personImageBase64: String,
        strength: Float,
    ): CommunityCreativeJobDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_compose_job")
        val payload = buildJsonObject {
            put("reference_post_id", JsonPrimitive(referencePostId))
            put("person_image_base64", JsonPrimitive(personImageBase64))
            put("strength", JsonPrimitive(strength.coerceIn(0f, 1f)))
        }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/compose/jobs",
            payload = payload,
        )
        parseCreativeJob(raw)
    }

    suspend fun cocreateCompose(
        serverUrl: String,
        bearerToken: String,
        referencePostId: Int,
        personAImageBase64: String,
        personBImageBase64: String,
        strength: Float,
    ): CommunityComposeResult = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_cocreate")
        val payload = buildJsonObject {
            put("reference_post_id", JsonPrimitive(referencePostId))
            put("person_a_image_base64", JsonPrimitive(personAImageBase64))
            put("person_b_image_base64", JsonPrimitive(personBImageBase64))
            put("strength", JsonPrimitive(strength.coerceIn(0f, 1f)))
        }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/cocreate/compose",
            payload = payload,
        )
        parseCompose(raw = raw, requestId = requestId)
    }

    suspend fun createCocreateJob(
        serverUrl: String,
        bearerToken: String,
        referencePostId: Int,
        personAImageBase64: String,
        personBImageBase64: String,
        strength: Float,
    ): CommunityCreativeJobDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_cocreate_job")
        val payload = buildJsonObject {
            put("reference_post_id", JsonPrimitive(referencePostId))
            put("person_a_image_base64", JsonPrimitive(personAImageBase64))
            put("person_b_image_base64", JsonPrimitive(personBImageBase64))
            put("strength", JsonPrimitive(strength.coerceIn(0f, 1f)))
        }.toString()
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/cocreate/jobs",
            payload = payload,
        )
        parseCreativeJob(raw)
    }

    suspend fun getCreativeJob(
        serverUrl: String,
        bearerToken: String,
        jobId: Int,
    ): CommunityCreativeJobDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_job_get")
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/community/jobs/$jobId")
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
            parseCreativeJob(raw)
        }
    }

    suspend fun retryCreativeJob(
        serverUrl: String,
        bearerToken: String,
        jobId: Int,
    ): CommunityCreativeJobDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_job_retry")
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/jobs/$jobId/retry",
            payload = "{}",
        )
        parseCreativeJob(raw)
    }

    suspend fun cancelCreativeJob(
        serverUrl: String,
        bearerToken: String,
        jobId: Int,
    ): CommunityCreativeJobDto = withContext(Dispatchers.IO) {
        val requestId = newRequestId("community_job_cancel")
        val raw = postJson(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            requestId = requestId,
            path = "/community/jobs/$jobId/cancel",
            payload = "{}",
        )
        parseCreativeJob(raw)
    }

    private fun postJson(
        serverUrl: String,
        bearerToken: String,
        requestId: String,
        path: String,
        payload: String,
    ): String {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}$path")
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
            return raw
        }
    }

    private fun parsePostsArray(array: JsonArray): List<CommunityPostDto> {
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            parsePost(obj.toString())
        }
    }

    private fun parsePost(raw: String): CommunityPostDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        val relayParent = (obj["relay_parent_summary"] as? JsonObject)?.let {
            CommunityRelayParentDto(
                id = it["id"]?.jsonPrimitive?.intOrNull ?: 0,
                userNickname = it["user_nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                placeTag = it["place_tag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sceneType = it["scene_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                imageUrl = it["image_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return CommunityPostDto(
            id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
            userId = obj["user_id"]?.jsonPrimitive?.intOrNull ?: 0,
            userNickname = obj["user_nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            feedbackId = obj["feedback_id"]?.jsonPrimitive?.intOrNull,
            imageUrl = obj["image_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sceneType = obj["scene_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            placeTag = obj["place_tag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            rating = obj["rating"]?.jsonPrimitive?.intOrNull ?: 0,
            reviewText = obj["review_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            caption = obj["caption"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            postType = obj["post_type"]?.jsonPrimitive?.contentOrNull ?: "normal",
            sourceType = obj["source_type"]?.jsonPrimitive?.contentOrNull ?: "feedback_flow",
            likeCount = obj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
            commentCount = obj["comment_count"]?.jsonPrimitive?.intOrNull ?: 0,
            likedByMe = obj["liked_by_me"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj["liked_by_me"]?.jsonPrimitive?.toString() == "true",
            styleTemplatePostId = obj["style_template_post_id"]?.jsonPrimitive?.intOrNull,
            relayParentSummary = relayParent,
            createdAt = obj["created_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
        )
    }

    private fun parseLike(raw: String): CommunityLikeResult {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityLikeResult(
            postId = obj["post_id"]?.jsonPrimitive?.intOrNull ?: 0,
            liked = obj["liked"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj["liked"]?.jsonPrimitive?.toString() == "true",
            likeCount = obj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun parseCommentsArray(array: JsonArray): List<CommunityCommentDto> {
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            parseComment(obj.toString())
        }
    }

    private fun parseComment(raw: String): CommunityCommentDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityCommentDto(
            id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
            postId = obj["post_id"]?.jsonPrimitive?.intOrNull ?: 0,
            userId = obj["user_id"]?.jsonPrimitive?.intOrNull ?: 0,
            userNickname = obj["user_nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = obj["created_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            canDelete = obj["can_delete"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj["can_delete"]?.jsonPrimitive?.toString() == "true",
        )
    }

    private fun parseRemakeGuide(raw: String): CommunityRemakeGuideDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        val templatePost = parsePost((obj["template_post"] ?: JsonObject(emptyMap())).toString())
        return CommunityRemakeGuideDto(
            templatePost = templatePost,
            shotScript = obj["shot_script"]?.jsonArray.orEmpty().map { it.jsonPrimitive.contentOrNull.orEmpty() },
            cameraHint = obj["camera_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            poseHint = obj["pose_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            framingHint = obj["framing_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            timingHint = obj["timing_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            alignmentChecks = obj["alignment_checks"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
            implementationStatus = obj["implementation_status"]?.jsonPrimitive?.contentOrNull ?: "ready",
            placeholderNotes = obj["placeholder_notes"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
        )
    }

    private fun parseRemakeAnalysis(raw: String): CommunityRemakeAnalysisDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityRemakeAnalysisDto(
            templatePostId = obj["template_post_id"]?.jsonPrimitive?.intOrNull ?: 0,
            poseScore = obj["pose_score"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
            framingScore = obj["framing_score"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
            alignmentScore = obj["alignment_score"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
            mismatchHints = obj["mismatch_hints"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
            implementationStatus = obj["implementation_status"]?.jsonPrimitive?.contentOrNull ?: "ready",
            placeholderNotes = obj["placeholder_notes"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
        )
    }

    private fun parseCompose(raw: String, requestId: String): CommunityComposeResult {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityComposeResult(
            imageBase64 = obj["composed_image_base64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            provider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            implementationStatus = obj["implementation_status"]?.jsonPrimitive?.contentOrNull ?: "ready",
            compareInputBase64 = obj["compare_input_base64"]?.jsonPrimitive?.contentOrNull,
            placeholderNotes = obj["placeholder_notes"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
            requestId = requestId,
        )
    }

    private fun parseCreativeJob(raw: String): CommunityCreativeJobDto {
        val obj = json.parseToJsonElement(raw).jsonObject
        return CommunityCreativeJobDto(
            jobId = obj["job_id"]?.jsonPrimitive?.intOrNull ?: 0,
            jobType = obj["job_type"]?.jsonPrimitive?.contentOrNull ?: "compose",
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "queued",
            progress = obj["progress"]?.jsonPrimitive?.intOrNull ?: 0,
            priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 100,
            retryCount = obj["retry_count"]?.jsonPrimitive?.intOrNull ?: 0,
            maxRetries = obj["max_retries"]?.jsonPrimitive?.intOrNull ?: 0,
            nextRetryAt = obj["next_retry_at"]?.jsonPrimitive?.intOrNull?.toLong(),
            startedAt = obj["started_at"]?.jsonPrimitive?.intOrNull?.toLong(),
            heartbeatAt = obj["heartbeat_at"]?.jsonPrimitive?.intOrNull?.toLong(),
            leaseExpiresAt = obj["lease_expires_at"]?.jsonPrimitive?.intOrNull?.toLong(),
            cancelReason = obj["cancel_reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            implementationStatus = obj["implementation_status"]?.jsonPrimitive?.contentOrNull ?: "ready",
            provider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            composedImageBase64 = obj["composed_image_base64"]?.jsonPrimitive?.contentOrNull,
            compareInputBase64 = obj["compare_input_base64"]?.jsonPrimitive?.contentOrNull,
            placeholderNotes = obj["placeholder_notes"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull.orEmpty() },
            errorMessage = obj["error_message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = obj["created_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            updatedAt = obj["updated_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            finishedAt = obj["finished_at"]?.jsonPrimitive?.intOrNull?.toLong(),
        )
    }
}

data class CommunityRelayParentDto(
    val id: Int,
    val userNickname: String,
    val placeTag: String,
    val sceneType: String,
    val imageUrl: String,
)

data class CommunityPostDto(
    val id: Int,
    val userId: Int,
    val userNickname: String,
    val feedbackId: Int?,
    val imageUrl: String,
    val sceneType: String,
    val placeTag: String,
    val rating: Int,
    val reviewText: String,
    val caption: String,
    val postType: String,
    val sourceType: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val styleTemplatePostId: Int?,
    val relayParentSummary: CommunityRelayParentDto?,
    val createdAt: Long,
)

data class CommunityCommentDto(
    val id: Int,
    val postId: Int,
    val userId: Int,
    val userNickname: String,
    val text: String,
    val createdAt: Long,
    val canDelete: Boolean,
)

data class CommunityLikeResult(
    val postId: Int,
    val liked: Boolean,
    val likeCount: Int,
)

data class CommunityRemakeGuideDto(
    val templatePost: CommunityPostDto,
    val shotScript: List<String>,
    val cameraHint: String,
    val poseHint: String,
    val framingHint: String,
    val timingHint: String,
    val alignmentChecks: List<String>,
    val implementationStatus: String,
    val placeholderNotes: List<String>,
)

data class CommunityRemakeAnalysisDto(
    val templatePostId: Int,
    val poseScore: Float,
    val framingScore: Float,
    val alignmentScore: Float,
    val mismatchHints: List<String>,
    val implementationStatus: String,
    val placeholderNotes: List<String>,
)

data class CommunityRecommendationDto(
    val post: CommunityPostDto,
    val score: Float,
    val reason: String,
)

data class CommunityFeedResult(
    val items: List<CommunityPostDto>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

data class CommunityComposeResult(
    val imageBase64: String,
    val provider: String,
    val model: String,
    val implementationStatus: String,
    val compareInputBase64: String?,
    val placeholderNotes: List<String>,
    val requestId: String,
)

data class CommunityCreativeJobDto(
    val jobId: Int,
    val jobType: String,
    val status: String,
    val progress: Int,
    val priority: Int,
    val retryCount: Int,
    val maxRetries: Int,
    val nextRetryAt: Long?,
    val startedAt: Long?,
    val heartbeatAt: Long?,
    val leaseExpiresAt: Long?,
    val cancelReason: String,
    val implementationStatus: String,
    val provider: String,
    val model: String,
    val composedImageBase64: String?,
    val compareInputBase64: String?,
    val placeholderNotes: List<String>,
    val errorMessage: String,
    val requestId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
)
