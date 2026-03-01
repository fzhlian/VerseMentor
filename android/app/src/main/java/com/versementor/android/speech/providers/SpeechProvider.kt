package com.versementor.android.speech.providers

import com.versementor.android.VoiceOption
import com.versementor.android.speech.AsrUtterancePolicy
import com.versementor.android.speech.AudioProcessingOptions
import com.versementor.android.speech.SpeechProviderId

data class SpeechProviderDescriptor(
    val id: SpeechProviderId,
    val displayName: String
)

data class SpeechListenRequest(
    val locale: String = "zh-CN",
    val partialResults: Boolean = true,
    val frameMs: Int = 20,
    val audioProcessing: AudioProcessingOptions = AudioProcessingOptions(),
    val utterancePolicy: AsrUtterancePolicy = AsrUtterancePolicy()
)

interface SpeechProviderCallbacks {
    fun onAsrReady()
    fun onAsrResult(text: String, isFinal: Boolean, confidence: Float?)
    fun onAsrError(code: Int, message: String)
    fun onSpeechDetected(level: Float)
    fun onSpeechStart()
    fun onSpeechEnd()
    fun onSpeakingChanged(speaking: Boolean)
    fun onSpeakError(code: Int, message: String)
    fun onDebug(message: String)
}

interface SpeechProvider {
    val descriptor: SpeechProviderDescriptor
    fun startListening(request: SpeechListenRequest): Boolean
    fun stopListening(reason: String = "app")
    fun speak(text: String, voiceId: String?)
    fun stopSpeak()
    fun setTtsVolume(volume: Float)
    fun listVoices(): List<VoiceOption>
    fun hasCapturedAudio(): Boolean
    fun isCapturePlaybackActive(): Boolean
    fun playCapturedAudio(): Boolean
    fun release()
}
