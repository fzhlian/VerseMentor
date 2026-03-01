package com.versementor.android.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.versementor.android.AccentToleranceState
import com.versementor.android.SettingsState

class PreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("versementor_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadSettings(): SettingsState {
        val followSystem = prefs.getBoolean("followSystem", true)
        val ttsVoiceId = prefs.getString("ttsVoiceId", "") ?: ""
        val ttsVoiceName = prefs.getString("ttsVoiceName", "") ?: ""
        val speechProviderId = prefs.getString("speechProviderId", "iflytek") ?: "iflytek"
        val allowListeningDuringSpeaking = prefs.getBoolean("allowListeningDuringSpeaking", false)
        val bargeInMode = prefs.getString("bargeInMode", "duck_tts") ?: "duck_tts"
        val duckVolume = prefs.getFloat("duckVolume", 0.25f).coerceIn(0f, 1f)
        val enableEchoCancellation = prefs.getBoolean("enableEchoCancellation", true)
        val enableNoiseSuppression = prefs.getBoolean("enableNoiseSuppression", true)
        val toneRemind = prefs.getBoolean("toneRemind", true)
        val variantsEnable = prefs.getBoolean("variantsEnable", true)
        val variantTtlDays = prefs.getInt("variantTtlDays", 7)
        val transientAsrPromptThreshold = prefs.getInt("transientAsrPromptThreshold", 3)
        val transientAsrRetryDelayMs = prefs.getInt("transientAsrRetryDelayMs", 350)
        val asrStopToStartCooldownMs = prefs.getInt("asrStopToStartCooldownMs", 220)
        val asrMinAcceptedSpeechMs = prefs.getInt("asrMinAcceptedSpeechMs", 260)
        val asrMinAcceptedSpeechFrames = prefs.getInt("asrMinAcceptedSpeechFrames", 4)
        val asrShortSpeechAcceptFrames = prefs.getInt("asrShortSpeechAcceptFrames", 10)

        val accentJson = prefs.getString("accentTolerance", null)
        val accent = accentJson?.let {
            gson.fromJson(it, AccentToleranceState::class.java)
        } ?: AccentToleranceState()

        val mappingsJson = prefs.getString("dynastyMappings", "[]") ?: "[]"
        val authorsJson = prefs.getString("authors", "[]") ?: "[]"

        val listType = object : TypeToken<List<String>>() {}.type
        val mappings = gson.fromJson<List<String>>(mappingsJson, listType) ?: emptyList()
        val authors = gson.fromJson<List<String>>(authorsJson, listType) ?: emptyList()

        return SettingsState(
            followSystem = followSystem,
            ttsVoiceId = ttsVoiceId,
            ttsVoiceName = ttsVoiceName,
            speechProviderId = speechProviderId,
            allowListeningDuringSpeaking = allowListeningDuringSpeaking,
            bargeInMode = bargeInMode,
            duckVolume = duckVolume,
            enableEchoCancellation = enableEchoCancellation,
            enableNoiseSuppression = enableNoiseSuppression,
            accentTolerance = accent,
            toneRemind = toneRemind,
            variantsEnable = variantsEnable,
            variantTtlDays = variantTtlDays,
            transientAsrPromptThreshold = transientAsrPromptThreshold,
            transientAsrRetryDelayMs = transientAsrRetryDelayMs,
            asrStopToStartCooldownMs = asrStopToStartCooldownMs,
            asrMinAcceptedSpeechMs = asrMinAcceptedSpeechMs,
            asrMinAcceptedSpeechFrames = asrMinAcceptedSpeechFrames,
            asrShortSpeechAcceptFrames = asrShortSpeechAcceptFrames,
            dynastyMappings = mappings,
            authors = authors
        )
    }

    fun saveSettings(settings: SettingsState) {
        prefs.edit()
            .putBoolean("followSystem", settings.followSystem)
            .putString("ttsVoiceId", settings.ttsVoiceId)
            .putString("ttsVoiceName", settings.ttsVoiceName)
            .putString("speechProviderId", settings.speechProviderId)
            .putBoolean("allowListeningDuringSpeaking", settings.allowListeningDuringSpeaking)
            .putString("bargeInMode", settings.bargeInMode)
            .putFloat("duckVolume", settings.duckVolume.coerceIn(0f, 1f))
            .putBoolean("enableEchoCancellation", settings.enableEchoCancellation)
            .putBoolean("enableNoiseSuppression", settings.enableNoiseSuppression)
            .putBoolean("toneRemind", settings.toneRemind)
            .putBoolean("variantsEnable", settings.variantsEnable)
            .putInt("variantTtlDays", settings.variantTtlDays)
            .putInt("transientAsrPromptThreshold", settings.transientAsrPromptThreshold)
            .putInt("transientAsrRetryDelayMs", settings.transientAsrRetryDelayMs)
            .putInt("asrStopToStartCooldownMs", settings.asrStopToStartCooldownMs)
            .putInt("asrMinAcceptedSpeechMs", settings.asrMinAcceptedSpeechMs)
            .putInt("asrMinAcceptedSpeechFrames", settings.asrMinAcceptedSpeechFrames)
            .putInt("asrShortSpeechAcceptFrames", settings.asrShortSpeechAcceptFrames)
            .putString("accentTolerance", gson.toJson(settings.accentTolerance))
            .putString("dynastyMappings", gson.toJson(settings.dynastyMappings))
            .putString("authors", gson.toJson(settings.authors))
            .apply()
    }

    fun clearVariantCache() {
        val keys = prefs.all.keys.filter { it.startsWith("variantCache:") }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    fun getVariantCache(): String? = prefs.getString("variantCache", null)

    fun setVariantCache(raw: String) {
        prefs.edit().putString("variantCache", raw).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
