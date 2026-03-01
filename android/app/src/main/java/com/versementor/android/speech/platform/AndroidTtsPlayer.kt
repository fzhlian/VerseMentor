package com.versementor.android.speech.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.versementor.android.VoiceOption
import java.util.Locale

class AndroidTtsPlayer(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val legacyFocusChangeListener = AudioManager.OnAudioFocusChangeListener {}
    private var ttsFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var duckResetRunnable: Runnable? = null
    private var ducked = false
    private var duckBaseVolume = -1
    private var ready = false
    private var released = false
    private var tts: TextToSpeech? = null

    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    var onDebug: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                onDebug?.invoke("TTS init failed status=$status")
                return@TextToSpeech
            }
            tts?.language = Locale.SIMPLIFIED_CHINESE
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (released) return
                    onSpeakingChanged?.invoke(true)
                }

                override fun onDone(utteranceId: String?) {
                    if (released) return
                    restoreVolumeIfNeeded()
                    abandonAudioFocus()
                    onSpeakingChanged?.invoke(false)
                }

                @Deprecated("Deprecated callback")
                override fun onError(utteranceId: String?) {
                    if (released) return
                    restoreVolumeIfNeeded()
                    abandonAudioFocus()
                    onSpeakingChanged?.invoke(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (released) return
                    restoreVolumeIfNeeded()
                    abandonAudioFocus()
                    onSpeakingChanged?.invoke(false)
                }
            })
        }
    }

    fun speak(text: String, voiceId: String?) {
        if (released) return
        if (!ready) {
            onDebug?.invoke("TTS not ready yet")
            return
        }
        requestAudioFocus()
        if (!voiceId.isNullOrBlank()) {
            val selected = tts?.voices?.firstOrNull { it.name == voiceId }
            if (selected != null) {
                tts?.voice = selected
            }
        }
        val utteranceId = "vm_utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        restoreVolumeIfNeeded()
        abandonAudioFocus()
    }

    fun duckTemporarily(durationMs: Long = 1200L) {
        val am = audioManager ?: return
        if (ducked) {
            scheduleDuckReset(durationMs)
            return
        }
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current <= 1) {
            scheduleDuckReset(durationMs)
            return
        }
        ducked = true
        duckBaseVolume = current
        val target = (current - 2).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        onDebug?.invoke("TTS ducked stream volume $current -> $target")
        scheduleDuckReset(durationMs)
    }

    fun listVoices(): List<VoiceOption> {
        val voices = tts?.voices?.filter { it.locale.language.startsWith("zh") } ?: emptySet()
        return voices.map { VoiceOption(it.name, it.name) }
    }

    fun release() {
        released = true
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        onSpeakingChanged = null
        onDebug = null
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (hasAudioFocus) return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener {}
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
        hasAudioFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ttsFocusRequest?.let { request ->
                am.abandonAudioFocusRequest(request)
            }
            ttsFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(legacyFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun scheduleDuckReset(delayMs: Long) {
        duckResetRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            restoreVolumeIfNeeded()
        }
        duckResetRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun restoreVolumeIfNeeded() {
        duckResetRunnable?.let { mainHandler.removeCallbacks(it) }
        duckResetRunnable = null
        if (!ducked) return
        ducked = false
        val am = audioManager ?: return
        val previous = duckBaseVolume
        duckBaseVolume = -1
        if (previous > 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
            onDebug?.invoke("TTS duck restored volume -> $previous")
        }
    }
}