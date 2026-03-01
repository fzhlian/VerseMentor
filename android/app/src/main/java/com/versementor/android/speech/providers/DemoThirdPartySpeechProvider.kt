package com.versementor.android.speech.providers

import android.content.Context
import com.versementor.android.speech.platform.AudioCaptureAndroid
import com.versementor.android.speech.platform.AudioCaptureConfig
import com.versementor.android.speech.platform.AudioCaptureObserver
import com.versementor.android.speech.platform.AudioOutputAndroid
import com.versementor.android.speech.platform.AudioOutputConfig
import com.versementor.android.speech.platform.AndroidTtsPlayer
import com.versementor.android.speech.platform.VadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

abstract class DemoThirdPartySpeechProvider(
    context: Context,
    final override val descriptor: SpeechProviderDescriptor,
    private val callbacks: SpeechProviderCallbacks,
    private val demoScript: List<String>
) : SpeechProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val microphone = AudioCaptureAndroid(context, scope)
    private val output = AudioOutputAndroid(context, scope)
    private val ttsPlayer = AndroidTtsPlayer(context)
    private val scriptCursor = AtomicInteger(0)
    private var listening = false
    private var speaking = false
    private var ignoreVadUntilMs = 0L
    private var utteranceActive = false
    private var utteranceSuppressed = false
    private var utteranceStartAtMs = 0L
    private var utteranceFrameCount = 0
    private var captureDrainJob: kotlinx.coroutines.Job? = null
    private val ttsTailIgnoreMs = 420L

    init {
        ttsPlayer.onDebug = { message ->
            callbacks.onDebug("${descriptor.displayName} TTS: $message")
        }
        ttsPlayer.onSpeakingChanged = { speaking ->
            this.speaking = speaking
            if (!speaking) {
                // Ignore short VAD bursts right after TTS ends to avoid self-triggered recognition.
                ignoreVadUntilMs = System.currentTimeMillis() + ttsTailIgnoreMs
            }
            callbacks.onSpeakingChanged(speaking)
        }
        ttsPlayer.onError = { code, message ->
            callbacks.onSpeakError(code, message)
        }
        output.onDebug = { message ->
            callbacks.onDebug("${descriptor.displayName} output: $message")
        }
        output.onError = { message ->
            callbacks.onDebug("${descriptor.displayName} output error: $message")
        }
    }

    override fun startListening(request: SpeechListenRequest): Boolean {
        if (listening) {
            callbacks.onDebug("${descriptor.displayName}: start ignored, already listening")
            return false
        }
        val frameMs = if (request.frameMs == 40) 40 else 20
        val minAcceptedSpeechMs = request.utterancePolicy.minAcceptedSpeechMs.coerceIn(120, 1200).toLong()
        val minAcceptedSpeechFrames = request.utterancePolicy.minAcceptedSpeechFrames.coerceIn(2, 40)
        val shortSpeechAcceptFrames = request.utterancePolicy.shortSpeechAcceptFrames
            .coerceIn(2, 40)
            .coerceAtLeast(minAcceptedSpeechFrames)
        val stream = microphone.start(
            config = AudioCaptureConfig(
                sampleRateHz = 16000,
                channels = 1,
                frameMs = frameMs,
                enableAec = request.audioProcessing.echoCancellation,
                enableNs = request.audioProcessing.noiseSuppression,
                vadSpeechStartLevel = 0.06f,
                vadSpeechEndSilenceFrames = if (frameMs == 40) 6 else 12
            ),
            nextObserver = object : AudioCaptureObserver {
                override fun onVolume(level: Float) {
                    callbacks.onSpeechDetected(level)
                    if (!utteranceActive || utteranceSuppressed) return
                    utteranceFrameCount += 1
                }

                override fun onVad(state: VadState) {
                    if (state == VadState.SPEECH_START) {
                        val now = System.currentTimeMillis()
                        val suppressed = speaking || now < ignoreVadUntilMs
                        utteranceActive = true
                        utteranceSuppressed = suppressed
                        utteranceStartAtMs = now
                        utteranceFrameCount = 0
                        if (suppressed) {
                            callbacks.onDebug("${descriptor.displayName}: speech start ignored (tts-bleed)")
                            return
                        }
                        callbacks.onDebug("${descriptor.displayName}: speech start")
                        callbacks.onSpeechStart()
                        return
                    }
                    if (!utteranceActive) {
                        callbacks.onDebug("${descriptor.displayName}: speech end")
                        callbacks.onSpeechEnd()
                        return
                    }
                    val durationMs = (System.currentTimeMillis() - utteranceStartAtMs).coerceAtLeast(0L)
                    val acceptedByDuration = durationMs >= minAcceptedSpeechMs
                    val acceptedByFrames =
                        durationMs < minAcceptedSpeechMs &&
                            utteranceFrameCount >= shortSpeechAcceptFrames
                    val validUtterance =
                        !utteranceSuppressed &&
                            utteranceFrameCount >= minAcceptedSpeechFrames &&
                            (acceptedByDuration || acceptedByFrames)
                    val ignoredByTts = utteranceSuppressed
                    utteranceActive = false
                    utteranceSuppressed = false
                    callbacks.onDebug("${descriptor.displayName}: speech end")
                    if (ignoredByTts) {
                        callbacks.onDebug("${descriptor.displayName}: speech end ignored (tts-bleed)")
                        return
                    }
                    callbacks.onSpeechEnd()
                    if (!listening) return
                    if (!validUtterance) {
                        callbacks.onDebug(
                            "${descriptor.displayName}: speech rejected duration=${durationMs}ms frames=$utteranceFrameCount"
                        )
                        return
                    }
                    if (acceptedByFrames) {
                        callbacks.onDebug(
                            "${descriptor.displayName}: short speech accepted duration=${durationMs}ms frames=$utteranceFrameCount"
                        )
                    }
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
                    utteranceActive = false
                    utteranceSuppressed = false
                    utteranceFrameCount = 0
                    callbacks.onAsrError(-1, message)
                }
            }
        )
        if (stream == null) {
            callbacks.onDebug("${descriptor.displayName}: microphone start failed")
            return false
        }

        listening = true
        captureDrainJob?.cancel()
        captureDrainJob = scope.launch {
            for (frame in stream) {
                if (!listening) {
                    break
                }
            }
        }

        callbacks.onAsrReady()
        callbacks.onDebug(
            "${descriptor.displayName}: listening locale=${request.locale} partial=${request.partialResults} ec=${request.audioProcessing.echoCancellation} ns=${request.audioProcessing.noiseSuppression} frameMs=$frameMs minMs=$minAcceptedSpeechMs minFrames=$minAcceptedSpeechFrames shortFrames=$shortSpeechAcceptFrames"
        )
        return true
    }

    override fun stopListening(reason: String) {
        if (!listening) {
            callbacks.onDebug("${descriptor.displayName}: stop ignored by $reason")
            return
        }
        listening = false
        utteranceActive = false
        utteranceSuppressed = false
        utteranceFrameCount = 0
        captureDrainJob?.cancel()
        captureDrainJob = null
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

    override fun setTtsVolume(volume: Float) {
        ttsPlayer.setVolumeScale(volume)
    }

    override fun listVoices() = ttsPlayer.listVoices()

    override fun hasCapturedAudio(): Boolean {
        return microphone.hasCapturedAudio()
    }

    override fun isCapturePlaybackActive(): Boolean {
        return output.isPlaying()
    }

    override fun playCapturedAudio(): Boolean {
        val data = microphone.snapshotCapturedAudio()
        if (data.isEmpty()) {
            return false
        }
        val channel = Channel<ByteArray>(capacity = Channel.BUFFERED)
        scope.launch {
            try {
                var offset = 0
                val chunkSize = 640
                while (offset < data.size) {
                    val end = (offset + chunkSize).coerceAtMost(data.size)
                    channel.send(data.copyOfRange(offset, end))
                    offset = end
                }
            } finally {
                channel.close()
            }
        }
        return output.playPcmStream(
            stream = channel,
            config = AudioOutputConfig(sampleRateHz = 16000, channels = 1)
        )
    }

    override fun release() {
        listening = false
        speaking = false
        utteranceActive = false
        utteranceSuppressed = false
        utteranceFrameCount = 0
        captureDrainJob?.cancel()
        captureDrainJob = null
        microphone.release()
        output.release()
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
