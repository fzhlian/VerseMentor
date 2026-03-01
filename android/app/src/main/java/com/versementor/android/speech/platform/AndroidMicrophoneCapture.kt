package com.versementor.android.speech.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

interface MicrophoneCaptureListener {
    fun onFrame(rms: Float)
    fun onSpeechStart()
    fun onSpeechEnd()
    fun onError(message: String)
}

data class MicrophoneCaptureOptions(
    val sampleRateHz: Int = 16000,
    val frameSize: Int = 320,
    val speechStartRmsThreshold: Float = 1100f,
    val speechEndSilenceFrames: Int = 10,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true
)

class AndroidMicrophoneCapture(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var speechActive = false
    private var silenceFrames = 0
    private var options = MicrophoneCaptureOptions()
    private var listener: MicrophoneCaptureListener? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val captureBuffer = ByteArrayOutputStream()
    private var playbackTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var playbackActive = false

    fun start(nextOptions: MicrophoneCaptureOptions, nextListener: MicrophoneCaptureListener): Boolean {
        if (running) {
            return false
        }
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            nextListener.onError("record audio permission denied")
            return false
        }

        options = nextOptions
        listener = nextListener
        captureBuffer.reset()
        speechActive = false
        silenceFrames = 0

        val minBuffer = AudioRecord.getMinBufferSize(
            options.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            nextListener.onError("invalid audio record buffer size")
            return false
        }

        val bufferSize = (minBuffer * 2).coerceAtLeast(options.frameSize * 2)
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(options.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                options.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            nextListener.onError("audio record not initialized")
            runCatching { record.release() }
            return false
        }

        audioRecord = record
        attachAudioEffects(record.audioSessionId)
        running = true

        runCatching { record.startRecording() }
            .onFailure {
                running = false
                cleanupRecorder()
                nextListener.onError(it.message ?: "audio record start failed")
                return false
            }

        val localFrame = ShortArray(options.frameSize)
        val thread = Thread {
            while (running) {
                val read = runCatching {
                    record.read(localFrame, 0, localFrame.size)
                }.getOrElse {
                    listener?.onError(it.message ?: "audio read failed")
                    running = false
                    0
                }
                if (read <= 0) {
                    continue
                }
                val rms = computeRms(localFrame, read)
                appendPcm(localFrame, read)
                listener?.onFrame(rms)
                processVad(rms)
            }
        }
        thread.name = "vm-mic-capture"
        thread.isDaemon = true
        captureThread = thread
        thread.start()
        return true
    }

    fun stop() {
        running = false
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(300)
        }
        captureThread = null
        if (speechActive) {
            speechActive = false
            listener?.onSpeechEnd()
        }
        cleanupRecorder()
    }

    fun release() {
        stopPlayback()
        stop()
        listener = null
    }

    fun hasCapturedAudio(): Boolean {
        return captureBuffer.size() > 0
    }

    fun isPlaybackActive(): Boolean {
        return playbackActive
    }

    fun playCapturedAudio(): Boolean {
        if (playbackActive) {
            return false
        }
        val bytes = captureBuffer.toByteArray()
        if (bytes.isEmpty()) {
            return false
        }
        stopPlayback()

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(options.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes.size)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                options.sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytes.size,
                AudioTrack.MODE_STATIC
            )
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        runCatching {
            track.write(bytes, 0, bytes.size)
            track.play()
        }.onFailure {
            runCatching { track.release() }
            return false
        }

        playbackTrack = track
        playbackActive = true
        val totalSamples = bytes.size / 2
        playbackThread = Thread {
            while (playbackActive) {
                val activeTrack = playbackTrack ?: break
                if (activeTrack.playbackHeadPosition >= totalSamples) {
                    break
                }
                Thread.sleep(30)
            }
            stopPlayback()
        }.apply {
            name = "vm-mic-playback"
            isDaemon = true
            start()
        }
        return true
    }

    fun stopPlayback() {
        playbackActive = false
        playbackThread?.interrupt()
        playbackThread = null
        playbackTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        playbackTrack = null
    }

    private fun processVad(rms: Float) {
        if (rms >= options.speechStartRmsThreshold) {
            silenceFrames = 0
            if (!speechActive) {
                speechActive = true
                listener?.onSpeechStart()
            }
            return
        }
        if (!speechActive) {
            return
        }
        silenceFrames += 1
        if (silenceFrames >= options.speechEndSilenceFrames) {
            silenceFrames = 0
            speechActive = false
            listener?.onSpeechEnd()
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        releaseAudioEffects()
        if (options.echoCancellation && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = true
                    aec = it
                }
            }
        }
        if (options.noiseSuppression && NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = true
                    ns = it
                }
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null
        ns = null
    }

    private fun cleanupRecorder() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        releaseAudioEffects()
    }

    private fun appendPcm(buffer: ShortArray, read: Int) {
        for (i in 0 until read) {
            val value = buffer[i].toInt()
            captureBuffer.write(value and 0xFF)
            captureBuffer.write((value shr 8) and 0xFF)
        }
    }

    private fun computeRms(buffer: ShortArray, read: Int): Float {
        if (read <= 0) return 0f
        var sum = 0.0
        for (i in 0 until read) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / read).toFloat()
    }
}
