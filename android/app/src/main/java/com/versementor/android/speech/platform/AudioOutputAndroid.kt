package com.versementor.android.speech.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioOutputConfig(
    val sampleRateHz: Int = 16000,
    val channels: Int = 1
)

class AudioOutputAndroid(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var active = false
    @Volatile
    private var volume = 1f

    var onPlaybackChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDebug: ((String) -> Unit)? = null

    fun playUrl(url: String): Boolean {
        synchronized(lock) {
            stopLocked()
            val player = MediaPlayer()
            mediaPlayer = player
            active = true
            onPlaybackChanged?.invoke(true)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setVolume(volume, volume)
            player.setOnCompletionListener {
                synchronized(lock) {
                    cleanupMediaPlayerLocked()
                    active = false
                }
                onPlaybackChanged?.invoke(false)
            }
            player.setOnErrorListener { _, what, extra ->
                synchronized(lock) {
                    cleanupMediaPlayerLocked()
                    active = false
                }
                onError?.invoke("media player error what=$what extra=$extra")
                onPlaybackChanged?.invoke(false)
                true
            }
            player.setOnPreparedListener {
                it.start()
                onDebug?.invoke("AudioOutput playUrl started: $url")
            }
            val parsed = Uri.parse(url)
            runCatching {
                if (parsed.scheme.isNullOrBlank()) {
                    player.setDataSource(url)
                } else {
                    player.setDataSource(context, parsed)
                }
                player.prepareAsync()
            }.onFailure { error ->
                cleanupMediaPlayerLocked()
                active = false
                onError?.invoke(error.message ?: "playUrl failed")
                onPlaybackChanged?.invoke(false)
                return false
            }
            return true
        }
    }

    fun playPcmStream(stream: ReceiveChannel<ByteArray>, config: AudioOutputConfig): Boolean {
        synchronized(lock) {
            stopLocked()
            val sampleRate = config.sampleRateHz
            val channelOut = if (config.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                channelOut,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                onError?.invoke("invalid audio track buffer size")
                return false
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(minBuffer + 1024))
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                onError?.invoke("audio track not initialized")
                return false
            }
            audioTrack = track
            runCatching {
                track.setVolume(volume)
                track.play()
            }.onFailure { error ->
                runCatching { track.release() }
                audioTrack = null
                onError?.invoke(error.message ?: "audio track play failed")
                return false
            }

            active = true
            onPlaybackChanged?.invoke(true)
            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    for (chunk in stream) {
                        if (!isActive) {
                            break
                        }
                        writeChunk(track, chunk)
                    }
                } catch (error: Throwable) {
                    onError?.invoke(error.message ?: "pcm playback failed")
                } finally {
                    synchronized(lock) {
                        cleanupAudioTrackLocked()
                        active = false
                    }
                    onPlaybackChanged?.invoke(false)
                }
            }
            onDebug?.invoke("AudioOutput playPcmStream started ${sampleRate}Hz/${config.channels}ch")
            return true
        }
    }

    fun setVolume(nextVolume: Float) {
        synchronized(lock) {
            volume = nextVolume.coerceIn(0f, 1f)
            runCatching { mediaPlayer?.setVolume(volume, volume) }
            runCatching { audioTrack?.setVolume(volume) }
        }
    }

    fun isPlaying(): Boolean {
        return active
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
        onPlaybackChanged?.invoke(false)
    }

    fun release() {
        stop()
        onPlaybackChanged = null
        onError = null
        onDebug = null
    }

    private fun writeChunk(track: AudioTrack, chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val written = track.write(chunk, offset, chunk.size - offset)
            if (written <= 0) {
                break
            }
            offset += written
        }
    }

    private fun stopLocked() {
        playbackJob?.cancel()
        playbackJob = null
        cleanupMediaPlayerLocked()
        cleanupAudioTrackLocked()
        active = false
    }

    private fun cleanupMediaPlayerLocked() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    private fun cleanupAudioTrackLocked() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }
}
