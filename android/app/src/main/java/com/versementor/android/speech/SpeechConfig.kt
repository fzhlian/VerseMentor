package com.versementor.android.speech

enum class SpeechProviderId(val rawValue: String, val displayName: String) {
    IFLYTEK(rawValue = "iflytek", displayName = "iFlytek"),
    VOLC_ASR(rawValue = "volc_asr", displayName = "Volc ASR"),
    VOLC_TTS_BIGMODEL(rawValue = "volc_tts_bigmodel", displayName = "Volc TTS BigModel");

    companion object {
        fun fromRaw(value: String?): SpeechProviderId {
            val normalized = when (value?.trim()?.lowercase().orEmpty()) {
                "volcengine",
                "volc" -> "volc_asr"
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
            return entries.firstOrNull { it.rawValue == normalized } ?: DUCK_TTS
        }
    }
}

data class AudioProcessingOptions(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true
)

data class AsrUtterancePolicy(
    val minAcceptedSpeechMs: Int = 260,
    val minAcceptedSpeechFrames: Int = 4,
    val shortSpeechAcceptFrames: Int = 10
)

data class DuplexPolicy(
    val allowListeningDuringSpeaking: Boolean = false,
    val bargeInMode: BargeInMode = BargeInMode.DUCK_TTS,
    val duckVolume: Float = 0.25f,
    val audioProcessing: AudioProcessingOptions = AudioProcessingOptions()
)

data class SpeechProviderOption(
    val id: String,
    val displayName: String
)
