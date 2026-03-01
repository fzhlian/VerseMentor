package com.versementor.android.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.versementor.android.R
import com.versementor.android.VoiceOption
import com.versementor.android.speech.providers.IFlytekSpeechProvider
import com.versementor.android.speech.providers.SpeechProvider
import com.versementor.android.speech.providers.SpeechProviderCallbacks
import com.versementor.android.speech.providers.SpeechProviderRegistry
import com.versementor.android.speech.providers.SpeechListenRequest
import com.versementor.android.speech.providers.VolcengineSpeechProvider
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface ISpeechIO {
    var onAsrResult: ((String, Boolean, Float?) -> Unit)?
    var onAsrError: ((Int, String) -> Unit)?
    var onAsrDebug: ((String) -> Unit)?
    var onSpeaking: ((Boolean) -> Unit)?
    fun startListening(): Boolean
    fun consumeStartFailureHandled(): Boolean
    fun stopListening(reason: String = "app")
    fun setStopToStartCooldownMs(cooldownMs: Int)
    fun speak(text: String, voiceId: String?)
    fun stopSpeak()
    fun listVoices(): List<VoiceOption>
    fun listSpeechProviders(): List<SpeechProviderOption>
    fun setSpeechProvider(providerId: String)
    fun setDuplexPolicy(policy: DuplexPolicy)
    fun isListeningDuringSpeakingEnabled(): Boolean
    fun hasCapturedAudio(): Boolean
    fun isCapturePlaybackActive(): Boolean
    fun playCapturedAudio(): Boolean
    fun release()
}

class SpeechIO(private val context: Context) : ISpeechIO {
    companion object {
        const val ERROR_START_LISTENING_FAILED = -1
        const val ERROR_SERVICE_UNAVAILABLE = -2
        const val ERROR_INSUFFICIENT_PERMISSIONS = -3
        const val ERROR_AUDIO = -4
        const val ERROR_NO_MATCH = -5
        const val ERROR_NETWORK = -6
        const val ERROR_NETWORK_TIMEOUT = -7
        const val ERROR_RECOGNIZER_BUSY = -8
        const val ERROR_SPEECH_TIMEOUT = -9
        private const val MAIN_THREAD_WAIT_MS = 1200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var startFailureHandled = false
    private var released = false
    private var lastStopRequestAtMs = 0L
    private var stopToStartCooldownMs = 220L
    private var lastBargeInAtMs = 0L
    private var lastVolumeLogAtMs = 0L
    private var vadSeenDuringSpeak = false
    private var activeProviderId = SpeechProviderId.IFLYTEK
    private val fallbackVolumeThreshold = 0.2f
    private val arbiter = FullDuplexAudioArbiter(
        DuplexPolicy(
            allowListeningDuringSpeaking = true,
            bargeInMode = BargeInMode.DUCK_TTS,
            audioProcessing = AudioProcessingOptions(
                echoCancellation = true,
                noiseSuppression = true
            )
        )
    )

    private val callbacks = object : SpeechProviderCallbacks {
        override fun onAsrReady() {
            if (released) return
            onAsrDebug?.invoke("AsrEvent.ready provider=${activeProviderId.rawValue}")
        }

        override fun onAsrResult(text: String, isFinal: Boolean, confidence: Float?) {
            if (released) return
            if (isFinal) {
                arbiter.onListeningStopped()
            }
            val eventType = if (isFinal) "final" else "partial"
            val confidencePart = confidence?.let { ", confidence=%.3f".format(it) } ?: ""
            onAsrDebug?.invoke("AsrEvent.$eventType text=\"${text.take(48)}\"$confidencePart")
            onAsrResult?.invoke(text, isFinal, confidence)
        }

        override fun onAsrError(code: Int, message: String) {
            if (released) return
            arbiter.onListeningStopped()
            val mapped = mapProviderError(code, message)
            onAsrDebug?.invoke("AsrEvent.error code=${mapped.first} message=${mapped.second}")
            onAsrError?.invoke(mapped.first, mapped.second)
        }

        override fun onSpeechDetected(level: Float) {
            if (released) return
            val now = System.currentTimeMillis()
            if (now - lastVolumeLogAtMs >= 240L) {
                lastVolumeLogAtMs = now
                onAsrDebug?.invoke("AsrEvent.volume level=%.3f".format(level.coerceIn(0f, 1f)))
            }
            if (vadSeenDuringSpeak) return
            if (level < fallbackVolumeThreshold) return
            if (now - lastBargeInAtMs < 500L) return
            val decision = arbiter.onSpeechDetected()
            lastBargeInAtMs = now
            applyBargeInDecision(decision, trigger = "volume")
        }

        override fun onSpeechStart() {
            if (released) return
            vadSeenDuringSpeak = true
            onAsrDebug?.invoke("AsrEvent.vad state=speech_start")
            val now = System.currentTimeMillis()
            if (now - lastBargeInAtMs < 500L) return
            lastBargeInAtMs = now
            val decision = arbiter.onSpeechStartDetected()
            applyBargeInDecision(decision, trigger = "vad")
        }

        override fun onSpeechEnd() {
            if (released) return
            onAsrDebug?.invoke("AsrEvent.vad state=speech_end")
            val decision = arbiter.onSpeechEndDetected()
            applyBargeInDecision(decision, trigger = "vad")
        }

        override fun onSpeakingChanged(speaking: Boolean) {
            if (released) return
            if (speaking) {
                vadSeenDuringSpeak = false
                onAsrDebug?.invoke("SpeakEvent.start provider=${activeProviderId.rawValue}")
                val transition = arbiter.onSpeakingStarted()
                if (transition.stopListening) {
                    activeProvider()?.stopListening(reason = "duplex-speaking-started")
                    onAsrDebug?.invoke("ASR duplex transition: ${transition.reason}")
                }
            } else {
                arbiter.onSpeakingStopped()
                vadSeenDuringSpeak = false
                onAsrDebug?.invoke("SpeakEvent.end provider=${activeProviderId.rawValue}")
                activeProvider()?.setTtsVolume(1f)
            }
            onSpeaking?.invoke(speaking)
        }

        override fun onSpeakError(code: Int, message: String) {
            if (released) return
            arbiter.onSpeakingStopped()
            onAsrDebug?.invoke("SpeakEvent.error code=$code message=$message")
            onSpeaking?.invoke(false)
        }

        override fun onDebug(message: String) {
            if (released) return
            onAsrDebug?.invoke(message)
        }
    }

    private val registry = SpeechProviderRegistry(
        listOf(
            IFlytekSpeechProvider(context.applicationContext, callbacks),
            VolcengineSpeechProvider(context.applicationContext, callbacks)
        )
    )

    override var onAsrResult: ((String, Boolean, Float?) -> Unit)? = null
    override var onAsrError: ((Int, String) -> Unit)? = null
    override var onAsrDebug: ((String) -> Unit)? = null
    override var onSpeaking: ((Boolean) -> Unit)? = null

    override fun startListening(): Boolean {
        return runOnMainSync(defaultValue = false) {
            startFailureHandled = false
            if (released) {
                onAsrDebug?.invoke("ASR start ignored: released")
                return@runOnMainSync false
            }
            val now = System.currentTimeMillis()
            val elapsedSinceStop = now - lastStopRequestAtMs
            if (elapsedSinceStop in 0 until stopToStartCooldownMs) {
                onAsrDebug?.invoke(
                    "ASR start deferred: cooldown ${stopToStartCooldownMs - elapsedSinceStop}ms"
                )
                return@runOnMainSync false
            }
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPermission) {
                startFailureHandled = true
                onAsrError?.invoke(
                    ERROR_INSUFFICIENT_PERMISSIONS,
                    context.getString(R.string.asr_error_insufficient_permissions)
                )
                return@runOnMainSync false
            }
            if (audioManager?.isMicrophoneMute == true) {
                startFailureHandled = true
                onAsrError?.invoke(
                    ERROR_AUDIO,
                    context.getString(R.string.asr_error_audio)
                )
                return@runOnMainSync false
            }
            val provider = activeProvider()
            if (provider == null) {
                startFailureHandled = true
                onAsrError?.invoke(
                    ERROR_SERVICE_UNAVAILABLE,
                    context.getString(R.string.asr_error_service_unavailable)
                )
                return@runOnMainSync false
            }

            val decision = arbiter.requestStartListening()
            if (!decision.allow) {
                onAsrDebug?.invoke("ASR start blocked by arbiter: ${decision.reason}")
                return@runOnMainSync false
            }

            val policy = arbiter.snapshotPolicy()
            val started = provider.startListening(
                SpeechListenRequest(
                    locale = Locale.SIMPLIFIED_CHINESE.toLanguageTag(),
                    partialResults = true,
                    frameMs = 20,
                    audioProcessing = policy.audioProcessing
                )
            )
            if (!started) {
                arbiter.onListeningStopped()
                startFailureHandled = true
                onAsrError?.invoke(
                    ERROR_START_LISTENING_FAILED,
                    context.getString(R.string.asr_error_start_listening_failed)
                )
                return@runOnMainSync false
            }
            onAsrDebug?.invoke(
                "ASR start provider=${activeProviderId.rawValue} allowDuplex=${policy.allowListeningDuringSpeaking} bargeIn=${policy.bargeInMode.rawValue} duckVolume=${policy.duckVolume} ec=${policy.audioProcessing.echoCancellation} ns=${policy.audioProcessing.noiseSuppression}"
            )
            true
        }
    }

    override fun consumeStartFailureHandled(): Boolean {
        return runOnMainSync(defaultValue = false) {
            val handled = startFailureHandled
            startFailureHandled = false
            handled
        }
    }

    override fun stopListening(reason: String) {
        runOnMainSync(defaultValue = Unit) {
            lastStopRequestAtMs = System.currentTimeMillis()
            activeProvider()?.stopListening(reason = reason)
            arbiter.onListeningStopped()
        }
    }

    override fun setStopToStartCooldownMs(cooldownMs: Int) {
        stopToStartCooldownMs = cooldownMs.coerceIn(0, 5000).toLong()
        onAsrDebug?.invoke("ASR stop-start cooldown -> ${stopToStartCooldownMs}ms")
    }

    override fun speak(text: String, voiceId: String?) {
        runOnMainSync(defaultValue = Unit) {
            if (released) return@runOnMainSync Unit
            activeProvider()?.speak(text, voiceId)
        }
    }

    override fun stopSpeak() {
        runOnMainSync(defaultValue = Unit) {
            activeProvider()?.stopSpeak()
            activeProvider()?.setTtsVolume(1f)
            arbiter.onSpeakingStopped()
        }
    }

    override fun listVoices(): List<VoiceOption> {
        return activeProvider()?.listVoices() ?: emptyList()
    }

    override fun listSpeechProviders(): List<SpeechProviderOption> {
        return registry.listDescriptors().map {
            SpeechProviderOption(id = it.id.rawValue, displayName = it.displayName)
        }
    }

    override fun setSpeechProvider(providerId: String) {
        runOnMainSync(defaultValue = Unit) {
            val resolved = SpeechProviderId.fromRaw(providerId)
            if (resolved == activeProviderId) {
                return@runOnMainSync Unit
            }
            activeProvider()?.stopListening(reason = "provider-switch")
            activeProvider()?.stopSpeak()
            arbiter.onListeningStopped()
            arbiter.onSpeakingStopped()
            activeProviderId = resolved
            onAsrDebug?.invoke("ASR provider switched -> ${resolved.rawValue}")
        }
    }

    override fun setDuplexPolicy(policy: DuplexPolicy) {
        runOnMainSync(defaultValue = Unit) {
            arbiter.updatePolicy(policy)
            onAsrDebug?.invoke(
                "ASR duplex policy -> allowDuringSpeak=${policy.allowListeningDuringSpeaking}, bargeIn=${policy.bargeInMode.rawValue}, duckVolume=${policy.duckVolume}, ec=${policy.audioProcessing.echoCancellation}, ns=${policy.audioProcessing.noiseSuppression}"
            )
        }
    }

    override fun isListeningDuringSpeakingEnabled(): Boolean {
        return arbiter.snapshotPolicy().allowListeningDuringSpeaking
    }

    override fun hasCapturedAudio(): Boolean {
        return activeProvider()?.hasCapturedAudio() == true
    }

    override fun isCapturePlaybackActive(): Boolean {
        return activeProvider()?.isCapturePlaybackActive() == true
    }

    override fun playCapturedAudio(): Boolean {
        return activeProvider()?.playCapturedAudio() == true
    }

    override fun release() {
        runOnMainSync(defaultValue = Unit) {
            released = true
            onAsrResult = null
            onAsrError = null
            onAsrDebug = null
            onSpeaking = null
            registry.releaseAll()
        }
    }

    private fun applyBargeInDecision(decision: BargeInDecision, trigger: String) {
        val provider = activeProvider() ?: return
        when {
            decision.stopTts -> {
                onAsrDebug?.invoke("ASR barge-in[$trigger] -> stop TTS (${decision.reason})")
                provider.stopSpeak()
            }

            decision.duckTts && decision.duckVolume != null -> {
                val duck = decision.duckVolume.coerceIn(0f, 1f)
                onAsrDebug?.invoke("ASR barge-in[$trigger] -> duck TTS to $duck (${decision.reason})")
                provider.setTtsVolume(duck)
                arbiter.onDuckApplied(duck)
            }

            decision.restoreVolume -> {
                onAsrDebug?.invoke("ASR barge-in[$trigger] -> restore TTS volume (${decision.reason})")
                provider.setTtsVolume(1f)
                arbiter.onDuckApplied(1f)
            }
        }
    }

    private fun activeProvider(): SpeechProvider? {
        return registry.get(activeProviderId)
    }

    private fun mapProviderError(code: Int, message: String): Pair<Int, String> {
        return when (code) {
            ERROR_INSUFFICIENT_PERMISSIONS -> code to context.getString(R.string.asr_error_insufficient_permissions)
            ERROR_AUDIO -> code to context.getString(R.string.asr_error_audio)
            ERROR_NO_MATCH -> code to context.getString(R.string.asr_error_no_match)
            ERROR_NETWORK -> code to context.getString(R.string.asr_error_network)
            ERROR_NETWORK_TIMEOUT -> code to context.getString(R.string.asr_error_network_timeout)
            ERROR_RECOGNIZER_BUSY -> code to context.getString(R.string.asr_error_recognizer_busy)
            ERROR_SPEECH_TIMEOUT -> code to context.getString(R.string.asr_error_speech_timeout)
            else -> code to if (message.isBlank()) {
                context.getString(R.string.asr_error_code, code)
            } else {
                message
            }
        }
    }

    private fun <T> runOnMainSync(defaultValue: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T?>(null)
        mainHandler.post {
            try {
                ref.set(block())
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)
        return if (completed) {
            ref.get() ?: defaultValue
        } else {
            defaultValue
        }
    }
}
