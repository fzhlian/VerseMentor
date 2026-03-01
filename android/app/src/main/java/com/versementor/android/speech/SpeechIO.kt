package com.versementor.android.speech

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.versementor.android.R
import com.versementor.android.VoiceOption
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
    fun stopListening()
    fun setStopToStartCooldownMs(cooldownMs: Int)
    fun speak(text: String, voiceId: String?)
    fun stopSpeak()
    fun listVoices(): List<VoiceOption>
    fun release()
}

class SpeechIO(private val context: Context) : ISpeechIO {
    companion object {
        const val ERROR_START_LISTENING_FAILED = -1
        const val ERROR_SERVICE_UNAVAILABLE = -2
        private const val DEFAULT_STOP_TO_START_COOLDOWN_MS = 220L
        private const val STOP_COMPLETION_TIMEOUT_BUFFER_MS = 320L
        private const val MIN_STOP_COMPLETION_TIMEOUT_MS = 450L
        private const val MAX_STOP_COMPLETION_TIMEOUT_MS = 1800L
        private const val LISTENING_STALE_TIMEOUT_MS = 9000L
        private const val STALE_TIMEOUT_INFRA_ERROR_THRESHOLD = 2
        private const val MAIN_THREAD_WAIT_MS = 1200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val speechRecognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        } else {
            null
        }
    private var tts: TextToSpeech? = null
    private var ttsFocusRequest: AudioFocusRequest? = null
    private var ttsHasAudioFocus = false
    private val legacyFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        onAsrDebug?.invoke("TTS audio focus changed: $change")
    }
    private var isListening = false
    private var isStopping = false
    private var lastStopRequestAtMs = 0L
    private var lastStartRequestAtMs = 0L
    private var lastRecognizerCallbackAtMs = 0L
    private var stopToStartCooldownMs = DEFAULT_STOP_TO_START_COOLDOWN_MS
    private var stopCompletionTimeoutRunnable: Runnable? = null
    private var listeningStaleTimeoutRunnable: Runnable? = null
    private var consecutiveListeningStaleTimeouts = 0
    private var suppressNextClientError = false
    private var isReleased = false
    override var onAsrResult: ((String, Boolean, Float?) -> Unit)? = null
    override var onAsrError: ((Int, String) -> Unit)? = null
    override var onAsrDebug: ((String) -> Unit)? = null
    override var onSpeaking: ((Boolean) -> Unit)? = null

    init {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isReleased) return
                touchRecognizerCallbackClock()
                onAsrDebug?.invoke("ASR readyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                if (isReleased) return
                touchRecognizerCallbackClock()
                onAsrDebug?.invoke("ASR beginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (isReleased) return
                touchRecognizerCallbackClock()
                onAsrDebug?.invoke("ASR endOfSpeech")
            }
            override fun onError(error: Int) {
                if (isReleased) return
                val shouldSuppressClientError =
                    error == SpeechRecognizer.ERROR_CLIENT && suppressNextClientError
                touchRecognizerCallbackClock(resetStaleTimeoutCounter = !shouldSuppressClientError)
                forceResetAsrState()
                if (shouldSuppressClientError) {
                    onAsrDebug?.invoke("ASR expected client error suppressed")
                    return
                }
                onAsrDebug?.invoke("ASR error($error): ${mapAsrError(error)}")
                onAsrError?.invoke(error, mapAsrError(error))
            }
            override fun onResults(results: Bundle?) {
                if (isReleased) return
                touchRecognizerCallbackClock()
                forceResetAsrState()
                suppressNextClientError = false
                val bundle = results ?: return
                val list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (text.isNullOrBlank()) {
                    onAsrDebug?.invoke("ASR final results empty -> no match")
                    onAsrError?.invoke(
                        SpeechRecognizer.ERROR_NO_MATCH,
                        mapAsrError(SpeechRecognizer.ERROR_NO_MATCH)
                    )
                    return
                }
                val confidence = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                onAsrResult?.invoke(text, true, confidence)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (isReleased) return
                touchRecognizerCallbackClock()
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                onAsrResult?.invoke(text, false, null)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {
                if (isReleased) return
                touchRecognizerCallbackClock()
                onAsrDebug?.invoke("ASR event type=$eventType")
            }
        })

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (isReleased) return
                        onSpeaking?.invoke(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        if (isReleased) return
                        abandonTtsAudioFocus()
                        onSpeaking?.invoke(false)
                    }
                    @Deprecated("Use onError(utteranceId: String?, errorCode: Int) on newer APIs.")
                    override fun onError(utteranceId: String?) {
                        if (isReleased) return
                        abandonTtsAudioFocus()
                        onSpeaking?.invoke(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (isReleased) return
                        abandonTtsAudioFocus()
                        onSpeaking?.invoke(false)
                    }
                })
            }
        }
    }

    override fun startListening(): Boolean {
        return runOnMainSync(defaultValue = false) {
            if (isReleased) {
                onAsrDebug?.invoke("ASR start ignored: released")
                return@runOnMainSync false
            }
            val recognizer = speechRecognizer
            if (recognizer == null) {
                onAsrDebug?.invoke("ASR unavailable on device")
                onAsrError?.invoke(ERROR_SERVICE_UNAVAILABLE, context.getString(R.string.asr_error_service_unavailable))
                return@runOnMainSync false
            }
            val now = System.currentTimeMillis()
            if (isListening && !isStopping) {
                val aliveAt = maxOf(lastStartRequestAtMs, lastRecognizerCallbackAtMs)
                val elapsedSinceRecognizerActivity = now - aliveAt
                if (aliveAt > 0 && elapsedSinceRecognizerActivity >= LISTENING_STALE_TIMEOUT_MS) {
                    onAsrDebug?.invoke(
                        "ASR listening stale ${elapsedSinceRecognizerActivity}ms -> cancel and force reset"
                    )
                    safeCancelRecognizer("stale-listening-recovery", suppressClientError = true)
                    forceResetAsrState()
                }
            }
            if (isStopping) {
                val elapsedSinceStop = now - lastStopRequestAtMs
                if (elapsedSinceStop >= stopCompletionTimeoutMs()) {
                    onAsrDebug?.invoke("ASR stop completion timeout reached ($elapsedSinceStop ms), force reset")
                    safeCancelRecognizer("stop-timeout-recovery", suppressClientError = true)
                    forceResetAsrState()
                }
            }
            if (isListening || isStopping) {
                onAsrDebug?.invoke("ASR start ignored: state listening=$isListening stopping=$isStopping")
                return@runOnMainSync false
            }
            val elapsedSinceStop = now - lastStopRequestAtMs
            if (elapsedSinceStop in 0 until stopToStartCooldownMs) {
                onAsrDebug?.invoke(
                    "ASR start deferred: cooldown ${stopToStartCooldownMs - elapsedSinceStop}ms after stop"
                )
                return@runOnMainSync false
            }
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPermission) {
                onAsrDebug?.invoke("ASR start blocked: RECORD_AUDIO permission denied")
                onAsrError?.invoke(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    context.getString(R.string.asr_error_insufficient_permissions)
                )
                return@runOnMainSync false
            }
            if (audioManager?.isMicrophoneMute == true) {
                onAsrDebug?.invoke("ASR start blocked: system microphone is muted")
                onAsrError?.invoke(
                    SpeechRecognizer.ERROR_AUDIO,
                    mapAsrError(SpeechRecognizer.ERROR_AUDIO)
                )
                return@runOnMainSync false
            }
            onAsrDebug?.invoke("ASR audio route: ${buildAudioRouteSnapshot()}")
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            }
            runCatching {
                cancelPendingStopCompletionTimeout()
                recognizer.startListening(intent)
                isListening = true
                isStopping = false
                suppressNextClientError = false
                lastStartRequestAtMs = now
                lastRecognizerCallbackAtMs = now
                scheduleListeningStaleTimeout(anchorStartAtMs = now)
                onAsrDebug?.invoke("ASR startListening")
                true
            }.onFailure {
                safeCancelRecognizer("start-failure", suppressClientError = true)
                forceResetAsrState()
                onAsrDebug?.invoke("ASR startListening failed: ${it.message ?: "unknown"}")
                onAsrError?.invoke(
                    ERROR_START_LISTENING_FAILED,
                    it.message ?: context.getString(R.string.asr_error_start_listening_failed)
                )
            }.getOrDefault(false)
        }
    }

    override fun stopListening() {
        runOnMainSync(defaultValue = Unit) {
            if (!isListening && !isStopping) return@runOnMainSync Unit
            val recognizer = speechRecognizer
            if (recognizer == null) {
                onAsrDebug?.invoke("ASR stop ignored: recognizer unavailable, force state reset")
                forceResetAsrState()
                return@runOnMainSync Unit
            }
            suppressNextClientError = true
            isStopping = true
            lastStopRequestAtMs = System.currentTimeMillis()
            consecutiveListeningStaleTimeouts = 0
            onAsrDebug?.invoke("ASR stopListening by app")
            cancelPendingListeningStaleTimeout()
            scheduleStopCompletionTimeout()
            runCatching {
                recognizer.stopListening()
            }.onFailure {
                onAsrDebug?.invoke("ASR stopListening failed: ${it.message ?: "unknown"}, force state reset")
                safeCancelRecognizer("stop-failure", suppressClientError = true)
                forceResetAsrState()
            }
        }
    }

    override fun setStopToStartCooldownMs(cooldownMs: Int) {
        stopToStartCooldownMs = cooldownMs.coerceIn(0, 5000).toLong()
        onAsrDebug?.invoke("ASR stop-start cooldown -> ${stopToStartCooldownMs}ms")
    }

    override fun speak(text: String, voiceId: String?) {
        runOnMainSync(defaultValue = Unit) {
            if (isReleased) return@runOnMainSync Unit
            requestTtsAudioFocus()
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utt_${System.currentTimeMillis()}")
            if (!voiceId.isNullOrBlank()) {
                val voice = tts?.voices?.firstOrNull { it.name == voiceId }
                if (voice != null) {
                    tts?.voice = voice
                }
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_${System.currentTimeMillis()}")
        }
    }

    override fun listVoices(): List<VoiceOption> {
        val voices = tts?.voices?.filter { it.locale.language.startsWith("zh") } ?: emptySet()
        return voices.map { VoiceOption(it.name, it.name) }
    }

    override fun stopSpeak() {
        runOnMainSync(defaultValue = Unit) {
            tts?.stop()
            abandonTtsAudioFocus()
        }
    }

    override fun release() {
        runOnMainSync(defaultValue = Unit) {
            isReleased = true
            onAsrResult = null
            onAsrError = null
            onAsrDebug = null
            onSpeaking = null
            isListening = false
            isStopping = false
            suppressNextClientError = true
            consecutiveListeningStaleTimeouts = 0
            cancelPendingListeningStaleTimeout()
            cancelPendingStopCompletionTimeout()
            val recognizer = speechRecognizer
            try {
                recognizer?.stopListening()
            } catch (_: Throwable) {
            }
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            }
            try {
                recognizer?.destroy()
            } catch (_: Throwable) {
            }
            tts?.stop()
            tts?.shutdown()
            tts = null
            abandonTtsAudioFocus()
        }
    }

    private fun requestTtsAudioFocus() {
        val am = audioManager ?: return
        if (ttsHasAudioFocus) return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    onAsrDebug?.invoke("TTS audio focus changed: $change")
                }
                .build()
            ttsFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                legacyFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        ttsHasAudioFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        onAsrDebug?.invoke("TTS audio focus ${if (ttsHasAudioFocus) "granted" else "denied"}")
    }

    private fun abandonTtsAudioFocus() {
        val am = audioManager ?: return
        if (!ttsHasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ttsFocusRequest?.let { request ->
                am.abandonAudioFocusRequest(request)
            }
            ttsFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(legacyFocusChangeListener)
        }
        ttsHasAudioFocus = false
        onAsrDebug?.invoke("TTS audio focus abandoned")
    }

    private fun scheduleStopCompletionTimeout() {
        cancelPendingStopCompletionTimeout()
        val stopRequestedAtMs = lastStopRequestAtMs
        val timeoutMs = stopCompletionTimeoutMs()
        val runnable = Runnable {
            if (isReleased) return@Runnable
            if (!isStopping) return@Runnable
            if (lastStopRequestAtMs != stopRequestedAtMs) return@Runnable
            onAsrDebug?.invoke("ASR stop timeout ${timeoutMs}ms -> force state reset")
            safeCancelRecognizer("stop-timeout", suppressClientError = true)
            forceResetAsrState()
        }
        stopCompletionTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, timeoutMs)
    }

    private fun cancelPendingStopCompletionTimeout() {
        val runnable = stopCompletionTimeoutRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        stopCompletionTimeoutRunnable = null
    }

    private fun scheduleListeningStaleTimeout(anchorStartAtMs: Long) {
        cancelPendingListeningStaleTimeout()
        val runnable = Runnable {
            if (isReleased) return@Runnable
            if (!isListening || isStopping) return@Runnable
            if (lastStartRequestAtMs != anchorStartAtMs) return@Runnable
            val now = System.currentTimeMillis()
            val aliveAt = maxOf(lastStartRequestAtMs, lastRecognizerCallbackAtMs)
            val elapsed = now - aliveAt
            if (aliveAt > 0 && elapsed < LISTENING_STALE_TIMEOUT_MS) {
                val nextRunnable = listeningStaleTimeoutRunnable
                if (nextRunnable != null) {
                    mainHandler.postDelayed(nextRunnable, LISTENING_STALE_TIMEOUT_MS - elapsed)
                }
                return@Runnable
            }
            onAsrDebug?.invoke("ASR listening timeout ${elapsed}ms -> cancel and emit speech timeout")
            safeCancelRecognizer("listening-timeout", suppressClientError = true)
            forceResetAsrState()
            consecutiveListeningStaleTimeouts += 1
            val staleCount = consecutiveListeningStaleTimeouts
            if (staleCount >= STALE_TIMEOUT_INFRA_ERROR_THRESHOLD) {
                val reason = context.getString(R.string.asr_error_start_listening_failed)
                onAsrDebug?.invoke(
                    "ASR listening timeout staleCount=$staleCount -> escalate infrastructure error"
                )
                onAsrError?.invoke(ERROR_START_LISTENING_FAILED, reason)
            } else {
                onAsrError?.invoke(
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    mapAsrError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                )
            }
        }
        listeningStaleTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, LISTENING_STALE_TIMEOUT_MS)
    }

    private fun cancelPendingListeningStaleTimeout() {
        val runnable = listeningStaleTimeoutRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        listeningStaleTimeoutRunnable = null
    }

    private fun stopCompletionTimeoutMs(): Long {
        return (stopToStartCooldownMs + STOP_COMPLETION_TIMEOUT_BUFFER_MS).coerceIn(
            MIN_STOP_COMPLETION_TIMEOUT_MS,
            MAX_STOP_COMPLETION_TIMEOUT_MS
        )
    }

    private fun forceResetAsrState() {
        cancelPendingListeningStaleTimeout()
        cancelPendingStopCompletionTimeout()
        isListening = false
        isStopping = false
        suppressNextClientError = false
        lastStartRequestAtMs = 0L
        lastRecognizerCallbackAtMs = 0L
    }

    private fun touchRecognizerCallbackClock(resetStaleTimeoutCounter: Boolean = true) {
        lastRecognizerCallbackAtMs = System.currentTimeMillis()
        if (resetStaleTimeoutCounter) {
            consecutiveListeningStaleTimeouts = 0
        }
        if (isListening && !isStopping && lastStartRequestAtMs > 0L) {
            scheduleListeningStaleTimeout(anchorStartAtMs = lastStartRequestAtMs)
        }
    }

    private fun safeCancelRecognizer(reason: String, suppressClientError: Boolean = false) {
        val recognizer = speechRecognizer ?: return
        runCatching {
            if (suppressClientError) {
                suppressNextClientError = true
            }
            recognizer.cancel()
            onAsrDebug?.invoke("ASR recognizer cancel by $reason")
        }
    }

    private fun buildAudioRouteSnapshot(): String {
        val am = audioManager ?: return "audioManager=null"
        val mode = am.mode
        val micMuted = am.isMicrophoneMute
        val musicActive = am.isMusicActive
        val speakerphone = am.isSpeakerphoneOn
        val wired = am.isWiredHeadsetOnCompat()
        val btSco = am.isBluetoothScoOn
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString("|") { it.typeLabel() }
            val output = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("|") { it.typeLabel() }
            "in=[$input],out=[$output]"
        } else {
            "in=[n/a],out=[n/a]"
        }
        return "mode=$mode,micMuted=$micMuted,musicActive=$musicActive,speaker=$speakerphone,wired=$wired,btSco=$btSco,$devices"
    }

    private fun AudioManager.isWiredHeadsetOnCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        } else {
            @Suppress("DEPRECATION")
            isWiredHeadsetOn
        }
    }

    private fun AudioDeviceInfo.typeLabel(): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
            else -> "type_$type"
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

    private fun mapAsrError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.asr_error_audio)
            SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.asr_error_client)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.asr_error_insufficient_permissions)
            SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.asr_error_network)
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.asr_error_network_timeout)
            SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.asr_error_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.asr_error_recognizer_busy)
            SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.asr_error_server)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.asr_error_speech_timeout)
            else -> context.getString(R.string.asr_error_code, error)
        }
    }
}
