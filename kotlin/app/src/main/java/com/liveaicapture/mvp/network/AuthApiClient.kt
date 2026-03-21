package com.liveaicapture.mvp.network

import com.liveaicapture.mvp.data.AuthUser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

class AuthApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun register(
        serverUrl: String,
        email: String,
        password: String,
        nickname: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
            if (nickname.isNotBlank()) put("nickname", JsonPrimitive(nickname.trim()))
        }
        requestAuth("${serverUrl.trimEnd('/')}/auth/register", payload.toString())
    }

    suspend fun login(
        serverUrl: String,
        email: String,
        password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
        }
        requestAuth("${serverUrl.trimEnd('/')}/auth/login", payload.toString())
    }

    suspend fun me(serverUrl: String, bearerToken: String): AuthUser = withContext(Dispatchers.IO) {
        val requestId = newRequestId("auth_me")
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/auth/me")
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
            parseUser(obj)
        }
    }

    suspend fun logout(serverUrl: String, bearerToken: String) = withContext(Dispatchers.IO) {
        val requestId = newRequestId("auth_logout")
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/auth/logout")
            .header("Authorization", "Bearer $bearerToken")
            .header("x-request-id", requestId)
            .post("{}".toRequestBody(requestMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
        }
    }

    private fun requestAuth(url: String, payload: String): AuthResult {
        val requestId = newRequestId("auth")
        val request = Request.Builder()
            .url(url)
            .header("x-request-id", requestId)
            .post(payload.toRequestBody(requestMediaType))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseRequestId = response.header("x-request-id").orEmpty().ifBlank { requestId }
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseHttpError(json, raw, response.code, responseRequestId))
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val user = parseUser(obj["user"]?.jsonObject ?: JsonObject(emptyMap()))
            val token = obj["bearer_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val expiresIn = obj["expires_in_sec"]?.jsonPrimitive?.intOrNull ?: 0
            if (token.isBlank()) throw IOException("登录凭证为空")
            AuthResult(user = user, bearerToken = token, expiresInSec = expiresIn)
        }
    }

    private fun parseUser(obj: JsonObject): AuthUser {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
        val email = obj["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val nickname = obj["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id <= 0 || email.isBlank()) throw IOException("用户信息不完整")
        return AuthUser(id = id, email = email, nickname = nickname.ifBlank { email.substringBefore("@") })
    }
}

data class AuthResult(
    val user: AuthUser,
    val bearerToken: String,
    val expiresInSec: Int,
)
