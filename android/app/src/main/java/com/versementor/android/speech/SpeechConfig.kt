package com.versementor.android.speech

enum class SpeechProviderId(val rawValue: String, val displayName: String) {
    IFLYTEK(rawValue = "iflytek", displayName = "iFlytek"),
    VOLCENGINE(rawValue = "volc", displayName = "Volcengine");

    companion object {
        fun fromRaw(value: String?): SpeechProviderId {
            val normalized = when (value?.trim()?.lowercase().orEmpty()) {
                "volcengine" -> "volc"
                else -> value?.trim()?.lowercase().orEmpty()
            }
            return entries.firstOrNull { it.rawValue == normalized } ?: IFLYTEK
        }
    }
}

enum class BargeInMode(val rawValue: String) {
    NONE(rawValue = "none"),
    DUCK_TTS(rawValue = "duck_tts"),
    STOP_TTS_ON_SPEECH(rawValue = "stop_tts_on_speech");

    companion object {
        fun fromRaw(value: String?): BargeInMode {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.rawValue == normalized } ?: STOP_TTS_ON_SPEECH
        }
    }
}

data class AudioProcessingOptions(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true
)

data class DuplexPolicy(
    val allowListeningDuringSpeaking: Boolean = true,
    val bargeInMode: BargeInMode = BargeInMode.STOP_TTS_ON_SPEECH,
    val duckVolume: Float = 0.4f,
    val audioProcessing: AudioProcessingOptions = AudioProcessingOptions()
)

data class SpeechProviderOption(
    val id: String,
    val displayName: String
)
