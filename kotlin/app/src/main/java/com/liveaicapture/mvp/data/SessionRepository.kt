package com.liveaicapture.mvp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(name = "live_ai_capture_session")

class SessionRepository(private val context: Context) {

    private object Keys {
        val bearerToken = stringPreferencesKey("bearer_token")
        val userId = intPreferencesKey("user_id")
        val userEmail = stringPreferencesKey("user_email")
        val userNickname = stringPreferencesKey("user_nickname")
        val expiresAtEpochSec = longPreferencesKey("expires_at_epoch_sec")
    }

    suspend fun readSession(): StoredSession {
        val pref = context.sessionStore.data.first()
        return StoredSession(
            bearerToken = pref[Keys.bearerToken] ?: "",
            userId = pref[Keys.userId] ?: 0,
            userEmail = pref[Keys.userEmail] ?: "",
            userNickname = pref[Keys.userNickname] ?: "",
            expiresAtEpochSec = pref[Keys.expiresAtEpochSec] ?: 0L,
        )
    }

    suspend fun saveSession(token: String, expiresInSec: Int, user: AuthUser) {
        val expiresAt = (System.currentTimeMillis() / 1000L) + expiresInSec.toLong()
        context.sessionStore.edit {
            it[Keys.bearerToken] = token
            it[Keys.userId] = user.id
            it[Keys.userEmail] = user.email
            it[Keys.userNickname] = user.nickname
            it[Keys.expiresAtEpochSec] = expiresAt
        }
    }

    suspend fun clearSession() {
        context.sessionStore.edit {
            it.remove(Keys.bearerToken)
            it.remove(Keys.userId)
            it.remove(Keys.userEmail)
            it.remove(Keys.userNickname)
            it.remove(Keys.expiresAtEpochSec)
        }
    }
}
