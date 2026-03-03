package com.versementor.android.speech.providers

import com.versementor.android.BuildConfig

data class VolcAsrConfig(
    val address: String,
    val uri: String,
    val cluster: String,
    val resourceId: String,
    val appId: String,
    val token: String,
    val uid: String
)

data class VolcTtsConfig(
    val address: String,
    val uri: String,
    val cluster: String,
    val resourceId: String,
    val appId: String,
    val token: String,
    val uid: String
)

object VolcConfigLoader {
    private fun pick(newValue: String, legacyValue: String, legacyName: String): Pair<String, String?> {
        val normalizedNew = newValue.trim()
        if (normalizedNew.isNotBlank()) {
            return normalizedNew to null
        }
        val normalizedLegacy = legacyValue.trim()
        if (normalizedLegacy.isBlank()) {
            return "" to null
        }
        return normalizedLegacy to "compat: using legacy key $legacyName"
    }

    fun loadAsrConfig(): Pair<VolcAsrConfig, List<String>> {
        val warnings = mutableListOf<String>()
        val appId = pick(BuildConfig.VOLC_ASR_APP_ID, BuildConfig.VOLCENGINE_APP_ID, "VOLCENGINE_APP_ID")
        val token = pick(BuildConfig.VOLC_ASR_TOKEN, BuildConfig.VOLCENGINE_TOKEN, "VOLCENGINE_TOKEN")
        val address = pick(BuildConfig.VOLC_ASR_ADDRESS, BuildConfig.VOLCENGINE_ASR_ADDRESS, "VOLCENGINE_ASR_ADDRESS")
        val uri = pick(BuildConfig.VOLC_ASR_URI, BuildConfig.VOLCENGINE_ASR_URI, "VOLCENGINE_ASR_URI")
        val cluster = pick(BuildConfig.VOLC_ASR_CLUSTER, BuildConfig.VOLCENGINE_ASR_CLUSTER, "VOLCENGINE_ASR_CLUSTER")
        val resourceId = pick(BuildConfig.VOLC_ASR_RESOURCE_ID, BuildConfig.VOLCENGINE_RESOURCE_ID, "VOLCENGINE_RESOURCE_ID")
        val uid = pick(BuildConfig.VOLC_ASR_UID, BuildConfig.VOLCENGINE_UID, "VOLCENGINE_UID")
        listOf(appId, token, address, uri, cluster, resourceId, uid).forEach { pair ->
            pair.second?.let(warnings::add)
        }
        return VolcAsrConfig(
            address = address.first,
            uri = uri.first,
            cluster = cluster.first,
            resourceId = resourceId.first,
            appId = appId.first,
            token = token.first,
            uid = uid.first
        ) to warnings
    }

    fun loadTtsConfig(): Pair<VolcTtsConfig, List<String>> {
        val warnings = mutableListOf<String>()
        val appId = pick(BuildConfig.VOLC_TTS_APP_ID, BuildConfig.VOLCENGINE_APP_ID, "VOLCENGINE_APP_ID")
        val token = pick(BuildConfig.VOLC_TTS_TOKEN, BuildConfig.VOLCENGINE_TOKEN, "VOLCENGINE_TOKEN")
        val address = pick(BuildConfig.VOLC_TTS_ADDRESS, BuildConfig.VOLCENGINE_ASR_ADDRESS, "VOLCENGINE_ASR_ADDRESS")
        val uri = pick(BuildConfig.VOLC_TTS_URI, BuildConfig.VOLCENGINE_ASR_URI, "VOLCENGINE_ASR_URI")
        val cluster = pick(BuildConfig.VOLC_TTS_CLUSTER, BuildConfig.VOLCENGINE_ASR_CLUSTER, "VOLCENGINE_ASR_CLUSTER")
        val resourceId = pick(BuildConfig.VOLC_TTS_RESOURCE_ID, BuildConfig.VOLCENGINE_RESOURCE_ID, "VOLCENGINE_RESOURCE_ID")
        val uid = pick(BuildConfig.VOLC_TTS_UID, BuildConfig.VOLCENGINE_UID, "VOLCENGINE_UID")
        listOf(appId, token, address, uri, cluster, resourceId, uid).forEach { pair ->
            pair.second?.let(warnings::add)
        }
        return VolcTtsConfig(
            address = address.first,
            uri = uri.first,
            cluster = cluster.first,
            resourceId = resourceId.first,
            appId = appId.first,
            token = token.first,
            uid = uid.first
        ) to warnings
    }
}
