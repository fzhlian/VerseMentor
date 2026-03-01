package com.versementor.android.speech.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

enum class VadState {
    SPEECH_START,
    SPEECH_END
}

data class AudioCaptureConfig(
    val sampleRateHz: Int = 16000,
    val channels: Int = 1,
    val frameMs: Int = 20,
    val enableAec: Boolean = true,
    val enableNs: Boolean = true,
    val vadSpeechStartLevel: Float = 0.06f,
    val vadSpeechEndSilenceFrames: Int = 10
)

interface AudioCaptureObserver {
    fun onVolume(level: Float)
    fun onVad(state: VadState)
    fun onError(message: String)
}

class AudioCaptureAndroid(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    @Volatile
    private var running = false
    private var captureJob: kotlinx.coroutines.Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var observer: AudioCaptureObserver? = null
    private var frameChannel: Channel<ByteArray>? = null
    @Volatile
    private var speechActive = false
    private var silenceFrames = 0
    private var activeConfig = AudioCaptureConfig()
    private val capturedPcm = ByteArrayOutputStream()

    fun start(config: AudioCaptureConfig, nextObserver: AudioCaptureObserver): ReceiveChannel<ByteArray>? {
        if (running) {
            return null
        }
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            nextObserver.onError("record audio permission denied")
            return null
        }

        val normalizedFrameMs = if (config.frameMs == 40) 40 else 20
        val normalizedConfig = config.copy(
            sampleRateHz = 16000,
            channels = 1,
            frameMs = normalizedFrameMs,
            vadSpeechStartLevel = config.vadSpeechStartLevel.coerceIn(0f, 1f),
            vadSpeechEndSilenceFrames = config.vadSpeechEndSilenceFrames.coerceAtLeast(1)
        )

        val frameSamples = normalizedConfig.sampleRateHz * normalizedConfig.frameMs / 1000
        val frameBytes = frameSamples * 2
        val minBuffer = AudioRecord.getMinBufferSize(
            normalizedConfig.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            nextObserver.onError("invalid audio record buffer size")
            return null
        }

        val bufferSize = (minBuffer * 2).coerceAtLeast(frameBytes * 2)
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(normalizedConfig.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                normalizedConfig.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            nextObserver.onError("audio record not initialized")
            runCatching { record.release() }
            return null
        }

        activeConfig = normalizedConfig
        observer = nextObserver
        running = true
        speechActive = false
        silenceFrames = 0
        synchronized(capturedPcm) {
            capturedPcm.reset()
        }

        val channel = Channel<ByteArray>(capacity = Channel.BUFFERED)
        frameChannel = channel
        audioRecord = record
        attachEffects(record.audioSessionId, normalizedConfig)

        runCatching { record.startRecording() }
            .onFailure { error ->
                running = false
                cleanupRecorder()
                frameChannel = null
                channel.close(error)
                nextObserver.onError(error.message ?: "audio record start failed")
                return null
            }

        captureJob = scope.launch(Dispatchers.IO) {
            val localBuffer = ByteArray(frameBytes)
            try {
                while (isActive && running) {
                    val read = runCatching {
                        record.read(localBuffer, 0, localBuffer.size)
                    }.getOrElse { error ->
                        observer?.onError(error.message ?: "audio read failed")
                        running = false
                        0
                    }
                    if (read <= 0) {
                        continue
                    }
                    val frame = if (read == localBuffer.size) {
                        localBuffer.copyOf()
                    } else {
                        localBuffer.copyOf(read)
                    }
                    synchronized(capturedPcm) {
                        capturedPcm.write(frame)
                    }
                    val level = computeVolume(frame)
                    observer?.onVolume(level)
                    processVad(level)
                    if (channel.trySend(frame).isFailure) {
                        break
                    }
                }
            } finally {
                channel.close()
                frameChannel = null
                if (speechActive) {
                    speechActive = false
                    observer?.onVad(VadState.SPEECH_END)
                }
                cleanupRecorder()
            }
        }

        return channel
    }

    fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
        captureJob?.cancel()
        captureJob = null
        frameChannel?.close()
        frameChannel = null
        if (speechActive) {
            speechActive = false
            observer?.onVad(VadState.SPEECH_END)
        }
        cleanupRecorder()
    }

    fun release() {
        stop()
        observer = null
    }

    fun hasCapturedAudio(): Boolean {
        return synchronized(capturedPcm) {
            capturedPcm.size() > 0
        }
    }

    fun snapshotCapturedAudio(): ByteArray {
        return synchronized(capturedPcm) {
            capturedPcm.toByteArray()
        }
    }

    private fun processVad(level: Float) {
        if (level >= activeConfig.vadSpeechStartLevel) {
            silenceFrames = 0
            if (!speechActive) {
                speechActive = true
                observer?.onVad(VadState.SPEECH_START)
            }
            return
        }
        if (!speechActive) {
            return
        }
        silenceFrames += 1
        if (silenceFrames >= activeConfig.vadSpeechEndSilenceFrames) {
            silenceFrames = 0
            speechActive = false
            observer?.onVad(VadState.SPEECH_END)
        }
    }

    private fun computeVolume(buffer: ByteArray): Float {
        if (buffer.size < 2) {
            return 0f
        }
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt().toDouble()
            sum += sample * sample
            samples += 1
            i += 2
        }
        if (samples <= 0) {
            return 0f
        }
        val rms = sqrt(sum / samples)
        return (rms / Short.MAX_VALUE.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun attachEffects(sessionId: Int, config: AudioCaptureConfig) {
        releaseEffects()
        if (config.enableAec && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = true
                    aec = it
                }
            }
        }
        if (config.enableNs && NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = true
                    ns = it
                }
            }
        }
    }

    private fun cleanupRecorder() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        releaseEffects()
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null
        ns = null
    }
}
