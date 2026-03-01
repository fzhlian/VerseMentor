package com.versementor.android.speech.providers

class SpeechProviderRegistry(providers: List<SpeechProvider>) {
    private val providerById = providers.associateBy { it.descriptor.id }

    fun listDescriptors(): List<SpeechProviderDescriptor> {
        return providerById.values.map { it.descriptor }.sortedBy { it.displayName }
    }

    fun get(id: com.versementor.android.speech.SpeechProviderId): SpeechProvider? {
        return providerById[id]
    }

    fun releaseAll() {
        providerById.values.forEach { provider ->
            provider.release()
        }
    }
}