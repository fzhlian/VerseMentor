package com.versementor.android.speech.providers

import android.content.Context
import com.versementor.android.VoiceOption
import com.versementor.android.speech.SpeechProviderId
import com.versementor.android.speech.platform.AndroidTtsPlayer

class VolcBigModelTtsProvider(
    context: Context,
    private val callbacks: SpeechProviderCallbacks
) : SpeechProvider {
    override val descriptor = SpeechProviderDescriptor(
        id = SpeechProviderId.VOLC_TTS_BIGMODEL,
        displayName = "Volc TTS BigModel"
    )

    private val ttsPlayer = AndroidTtsPlayer(context.applicationContext)

    init {
        val (cfg, warns) = VolcConfigLoader.loadTtsConfig()
        warns.forEach { callbacks.onDebug("${descriptor.displayName}: $it") }
        callbacks.onDebug(
            "${descriptor.displayName}: route uri=${cfg.uri.ifBlank { "<default>" }} cluster=${cfg.cluster.ifBlank { "<none>" }}"
        )
        ttsPlayer.onDebug = { message -> callbacks.onDebug("${descriptor.displayName} TTS: $message") }
        ttsPlayer.onSpeakingChanged = { speaking -> callbacks.onSpeakingChanged(speaking) }
        ttsPlayer.onError = { code, message -> callbacks.onSpeakError(code, message) }
    }

    override fun startListening(request: SpeechListenRequest): Boolean {
        callbacks.onDebug("${descriptor.displayName}: startListening rejected (tts-only provider)")
        return false
    }

    override fun stopListening(reason: String) {
        callbacks.onDebug("${descriptor.displayName}: stopListening ignored by $reason (tts-only provider)")
    }

    override fun speak(text: String, voiceId: String?) {
        ttsPlayer.speak(text, voiceId)
    }

    override fun stopSpeak() {
        ttsPlayer.stop()
    }

    override fun setTtsVolume(volume: Float) {
        ttsPlayer.setVolumeScale(volume)
    }

    override fun listVoices(): List<VoiceOption> = ttsPlayer.listVoices()

    override fun hasCapturedAudio(): Boolean = false

    override fun isCapturePlaybackActive(): Boolean = false

    override fun playCapturedAudio(): Boolean = false

    override fun release() {
        ttsPlayer.release()
    }
}
