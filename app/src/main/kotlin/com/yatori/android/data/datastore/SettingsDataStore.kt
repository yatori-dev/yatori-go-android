package com.yatori.android.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yatori.android.domain.model.AiProvider
import com.yatori.android.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("yatori_settings")

@Singleton
class SettingsDataStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object Keys {
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val LOG_MODEL = intPreferencesKey("log_model")
        val EMAIL_SW = booleanPreferencesKey("email_sw")
        val SMTP_HOST = stringPreferencesKey("smtp_host")
        val SMTP_PORT = intPreferencesKey("smtp_port")
        val SMTP_USER = stringPreferencesKey("smtp_user")
        val SMTP_PASS = stringPreferencesKey("smtp_pass")
        val AI_TYPE = stringPreferencesKey("ai_type")
        val AI_URL = stringPreferencesKey("ai_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_KEY = stringPreferencesKey("ai_key")
        val API_QUE_URL = stringPreferencesKey("api_que_url")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val FOLLOW_SYSTEM = booleanPreferencesKey("follow_system")
    }

    val settings: Flow<AppSettings> = ctx.dataStore.data.map { p ->
        AppSettings(
            logLevel = p[Keys.LOG_LEVEL] ?: "INFO",
            logModel = p[Keys.LOG_MODEL] ?: 0,
            emailSw = p[Keys.EMAIL_SW] ?: false,
            smtpHost = p[Keys.SMTP_HOST] ?: "",
            smtpPort = p[Keys.SMTP_PORT] ?: 465,
            smtpUser = p[Keys.SMTP_USER] ?: "",
            smtpPassword = p[Keys.SMTP_PASS] ?: "",
            aiType = AiProvider.fromCode(p[Keys.AI_TYPE] ?: "TONGYI"),
            aiUrl = p[Keys.AI_URL] ?: "",
            aiModel = p[Keys.AI_MODEL] ?: "",
            aiApiKey = p[Keys.AI_KEY] ?: "",
            apiQueUrl = p[Keys.API_QUE_URL] ?: "http://localhost:8083",
            darkMode = p[Keys.DARK_MODE] ?: false,
            followSystem = p[Keys.FOLLOW_SYSTEM] ?: true
        )
    }

    val onboarded: Flow<Boolean> = ctx.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun save(s: AppSettings) = ctx.dataStore.edit { p ->
        p[Keys.LOG_LEVEL] = s.logLevel
        p[Keys.LOG_MODEL] = s.logModel
        p[Keys.EMAIL_SW] = s.emailSw
        p[Keys.SMTP_HOST] = s.smtpHost
        p[Keys.SMTP_PORT] = s.smtpPort
        p[Keys.SMTP_USER] = s.smtpUser
        p[Keys.SMTP_PASS] = s.smtpPassword
        p[Keys.AI_TYPE] = s.aiType.code
        p[Keys.AI_URL] = s.aiUrl
        p[Keys.AI_MODEL] = s.aiModel
        p[Keys.AI_KEY] = s.aiApiKey
        p[Keys.API_QUE_URL] = s.apiQueUrl
        p[Keys.DARK_MODE] = s.darkMode
        p[Keys.FOLLOW_SYSTEM] = s.followSystem
    }

    suspend fun setOnboarded() = ctx.dataStore.edit { it[Keys.ONBOARDED] = true }
}
