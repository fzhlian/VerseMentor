package com.versementor.android.speech.providers

import android.content.Context
import com.versementor.android.speech.platform.AndroidMicrophoneCapture
import com.versementor.android.speech.platform.AndroidTtsPlayer
import com.versementor.android.speech.platform.MicrophoneCaptureListener
import com.versementor.android.speech.platform.MicrophoneCaptureOptions
import java.util.concurrent.atomic.AtomicInteger

abstract class DemoThirdPartySpeechProvider(
    context: Context,
    final override val descriptor: SpeechProviderDescriptor,
    private val callbacks: SpeechProviderCallbacks,
    private val demoScript: List<String>
) : SpeechProvider {
    private val microphone = AndroidMicrophoneCapture(context)
    private val ttsPlayer = AndroidTtsPlayer(context)
    private val scriptCursor = AtomicInteger(0)
    private var listening = false
    private var lastRequest = SpeechListenRequest()

    init {
        ttsPlayer.onDebug = { message ->
            callbacks.onDebug("${descriptor.displayName} TTS: $message")
        }
        ttsPlayer.onSpeakingChanged = { speaking ->
            callbacks.onSpeakingChanged(speaking)
        }
    }

    override fun startListening(request: SpeechListenRequest): Boolean {
        if (listening) {
            callbacks.onDebug("${descriptor.displayName}: start ignored, already listening")
            return false
        }
        lastRequest = request
        val started = microphone.start(
            nextOptions = MicrophoneCaptureOptions(
                sampleRateHz = 16000,
                frameSize = 320,
                speechStartRmsThreshold = 1050f,
                speechEndSilenceFrames = 12,
                echoCancellation = request.audioProcessing.echoCancellation,
                noiseSuppression = request.audioProcessing.noiseSuppression
            ),
            nextListener = object : MicrophoneCaptureListener {
                override fun onFrame(rms: Float) {
                    callbacks.onSpeechDetected(rms)
                }

                override fun onSpeechStart() {
                    callbacks.onDebug("${descriptor.displayName}: speech start")
                    callbacks.onSpeechDetected(1500f)
                    if (!request.partialResults) return
                    val preview = buildPreviewResult()
                    if (preview.isNotBlank()) {
                        callbacks.onAsrResult(preview, false, null)
                    }
                }

                override fun onSpeechEnd() {
                    callbacks.onDebug("${descriptor.displayName}: speech end")
                    if (!listening) return
                    listening = false
                    microphone.stop()
                    val text = nextTranscript()
                    if (text.isBlank()) {
                        callbacks.onAsrError(-5, "no match")
                    } else {
                        callbacks.onAsrResult(text, true, 0.88f)
                    }
                }

                override fun onError(message: String) {
                    callbacks.onDebug("${descriptor.displayName}: mic error: $message")
                    listening = false
                    callbacks.onAsrError(-1, message)
                }
            }
        )
        if (!started) {
            callbacks.onDebug("${descriptor.displayName}: microphone start failed")
            return false
        }
        listening = true
        callbacks.onDebug(
            "${descriptor.displayName}: listening locale=${request.locale} partial=${request.partialResults} ec=${request.audioProcessing.echoCancellation} ns=${request.audioProcessing.noiseSuppression}"
        )
        return true
    }

    override fun stopListening(reason: String) {
        if (!listening) {
            callbacks.onDebug("${descriptor.displayName}: stop ignored by $reason")
            return
        }
        listening = false
        microphone.stop()
        callbacks.onDebug("${descriptor.displayName}: listening stopped by $reason")
    }

    override fun speak(text: String, voiceId: String?) {
        callbacks.onDebug("${descriptor.displayName}: speak \"${text.take(32)}\"")
        ttsPlayer.speak(text, voiceId)
    }

    override fun stopSpeak() {
        ttsPlayer.stop()
    }

    override fun duckCurrentTts() {
        ttsPlayer.duckTemporarily()
    }

    override fun listVoices() = ttsPlayer.listVoices()

    override fun hasCapturedAudio(): Boolean {
        return microphone.hasCapturedAudio()
    }

    override fun isCapturePlaybackActive(): Boolean {
        return microphone.isPlaybackActive()
    }

    override fun playCapturedAudio(): Boolean {
        return microphone.playCapturedAudio()
    }

    override fun release() {
        listening = false
        microphone.release()
        ttsPlayer.release()
    }

    private fun nextTranscript(): String {
        if (demoScript.isEmpty()) {
            return ""
        }
        val index = scriptCursor.getAndIncrement()
        return demoScript[index % demoScript.size]
    }

    private fun buildPreviewResult(): String {
        val index = scriptCursor.get().coerceAtLeast(0)
        val candidate = if (demoScript.isEmpty()) "" else demoScript[index % demoScript.size]
        if (candidate.length <= 2) return candidate
        val previewLength = (candidate.length / 2).coerceAtLeast(2)
        return candidate.take(previewLength)
    }
}