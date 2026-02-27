package com.versementor.android.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.versementor.android.VoiceOption
import java.util.Locale

interface ISpeechIO {
    var onAsrResult: ((String, Boolean, Float?) -> Unit)?
    var onAsrError: ((Int, String) -> Unit)?
    var onSpeaking: ((Boolean) -> Unit)?
    fun startListening()
    fun stopListening()
    fun speak(text: String, voiceId: String?)
    fun stopSpeak()
    fun listVoices(): List<VoiceOption>
    fun release()
}

class SpeechIO(private val context: Context) : ISpeechIO {
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var tts: TextToSpeech? = null
    override var onAsrResult: ((String, Boolean, Float?) -> Unit)? = null
    override var onAsrError: ((Int, String) -> Unit)? = null
    override var onSpeaking: ((Boolean) -> Unit)? = null

    init {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onAsrError?.invoke(error, mapAsrError(error))
            }
            override fun onResults(results: Bundle?) {
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching {
            speechRecognizer.startListening(intent)
        }.onFailure {
            onAsrError?.invoke(-1, it.message ?: "startListening failed")
        }
    }

    override fun stopListening() {
        speechRecognizer.stopListening()
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
        onSpeaking = null
        try {
            speechRecognizer.stopListening()
        } catch (_: Throwable) {
        }
        try {
            speechRecognizer.cancel()
        } catch (_: Throwable) {
        }
        try {
            speechRecognizer.destroy()
        } catch (_: Throwable) {
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun mapAsrError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            else -> "error code $error"
        }
    }
}
