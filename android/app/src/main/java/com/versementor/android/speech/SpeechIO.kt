package com.versementor.android.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.versementor.android.R
import com.versementor.android.VoiceOption
import java.util.Locale

interface ISpeechIO {
    var onAsrResult: ((String, Boolean, Float?) -> Unit)?
    var onAsrError: ((Int, String) -> Unit)?
    var onAsrDebug: ((String) -> Unit)?
    var onSpeaking: ((Boolean) -> Unit)?
    fun startListening()
    fun stopListening()
    fun speak(text: String, voiceId: String?)
    fun stopSpeak()
    fun listVoices(): List<VoiceOption>
    fun release()
}

class SpeechIO(private val context: Context) : ISpeechIO {
    companion object {
        const val ERROR_START_LISTENING_FAILED = -1
        const val ERROR_SERVICE_UNAVAILABLE = -2
    }

    private val speechRecognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        } else {
            null
        }
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var suppressNextClientError = false
    override var onAsrResult: ((String, Boolean, Float?) -> Unit)? = null
    override var onAsrError: ((Int, String) -> Unit)? = null
    override var onAsrDebug: ((String) -> Unit)? = null
    override var onSpeaking: ((Boolean) -> Unit)? = null

    init {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
                if (error == SpeechRecognizer.ERROR_CLIENT && suppressNextClientError) {
                    onAsrDebug?.invoke("ASR expected client error suppressed")
                    suppressNextClientError = false
                    return
                }
                suppressNextClientError = false
                onAsrDebug?.invoke("ASR error($error): ${mapAsrError(error)}")
                onAsrError?.invoke(error, mapAsrError(error))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                suppressNextClientError = false
                val bundle = results ?: return
                val list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                val confidence = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                onAsrResult?.invoke(text, true, confidence)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                onAsrResult?.invoke(text, false, null)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeaking?.invoke(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        onSpeaking?.invoke(false)
                    }
                    @Deprecated("Use onError(utteranceId: String?, errorCode: Int) on newer APIs.")
                    override fun onError(utteranceId: String?) {
                        onSpeaking?.invoke(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSpeaking?.invoke(false)
                    }
                })
            }
        }
    }

    override fun startListening() {
        if (isListening) {
            onAsrDebug?.invoke("ASR start ignored: already listening")
            return
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
            return
        }
        val recognizer = speechRecognizer
        if (recognizer == null) {
            onAsrDebug?.invoke("ASR unavailable on device")
            onAsrError?.invoke(ERROR_SERVICE_UNAVAILABLE, context.getString(R.string.asr_error_service_unavailable))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching {
            recognizer.startListening(intent)
            isListening = true
            suppressNextClientError = false
            onAsrDebug?.invoke("ASR startListening")
        }.onFailure {
            isListening = false
            suppressNextClientError = false
            onAsrDebug?.invoke("ASR startListening failed: ${it.message ?: "unknown"}")
            onAsrError?.invoke(
                ERROR_START_LISTENING_FAILED,
                it.message ?: context.getString(R.string.asr_error_start_listening_failed)
            )
        }
    }

    override fun stopListening() {
        if (!isListening) return
        val recognizer = speechRecognizer ?: return
        suppressNextClientError = true
        onAsrDebug?.invoke("ASR stopListening by app")
        runCatching {
            recognizer.stopListening()
        }
        isListening = false
    }

    override fun speak(text: String, voiceId: String?) {
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

    override fun listVoices(): List<VoiceOption> {
        val voices = tts?.voices?.filter { it.locale.language.startsWith("zh") } ?: emptySet()
        return voices.map { VoiceOption(it.name, it.name) }
    }

    override fun stopSpeak() {
        tts?.stop()
    }

    override fun release() {
        onAsrResult = null
        onAsrError = null
        onAsrDebug = null
        onSpeaking = null
        isListening = false
        suppressNextClientError = true
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
