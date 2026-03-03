package com.versementor.android.speech.providers

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.versementor.android.VoiceOption
import com.versementor.android.speech.SpeechProviderId
import com.versementor.android.speech.platform.AudioCaptureAndroid
import com.versementor.android.speech.platform.AudioCaptureConfig
import com.versementor.android.speech.platform.AudioCaptureObserver
import com.versementor.android.speech.platform.AudioOutputAndroid
import com.versementor.android.speech.platform.AudioOutputConfig
import com.versementor.android.speech.platform.VadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.min

class VolcStreamingAsrProvider(
    context: Context,
    private val callbacks: SpeechProviderCallbacks
) : SpeechProvider {
    override val descriptor = SpeechProviderDescriptor(
        id = SpeechProviderId.VOLC_ASR,
        displayName = "Volc ASR"
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val microphone = AudioCaptureAndroid(appContext, scope)
    private val output = AudioOutputAndroid(appContext, scope)
    private val engineLock = Any()
    private var engine: SpeechEngine? = null
    private var environmentPrepared = false

    @Volatile
    private var listening = false

    @Volatile
    private var speaking = false

    @Volatile
    private var released = false

    private var ignoreVadUntilMs = 0L
    private var ignoreEngineMessageUntilMs = 0L
    private var finishDirectiveSent = false
    private var utteranceActive = false
    private var utteranceSuppressed = false
    private var utteranceStartAtMs = 0L
    private var utteranceFrameCount = 0
    private var emitPartialResults = true
    private var captureDrainJob: kotlinx.coroutines.Job? = null
    private val ttsTailIgnoreMs = 420L

    private val speechListener = SpeechEngine.SpeechListener { messageType, data, dataLen ->
        handleEngineMessage(messageType, data, dataLen)
    }

    init {
        maybePrepareEnvironment()
        output.onDebug = { message ->
            callbacks.onDebug("${descriptor.displayName} output: $message")
        }
        output.onError = { message ->
            callbacks.onDebug("${descriptor.displayName} output error: $message")
        }
    }

    override fun startListening(request: SpeechListenRequest): Boolean {
        if (released) {
            callbacks.onDebug("${descriptor.displayName}: start ignored, released")
            return false
        }
        if (listening) {
            callbacks.onDebug("${descriptor.displayName}: start ignored, already listening")
            return false
        }

        val (cfg, warnings) = runtimeConfig()
        warnings.forEach { callbacks.onDebug("${descriptor.displayName}: $it") }
        val (ttsCfg, ttsWarnings) = VolcConfigLoader.loadTtsConfig()
        ttsWarnings.forEach { callbacks.onDebug("${descriptor.displayName}: $it") }
        callbacks.onDebug(
            "${descriptor.displayName}: ASR route uri=${cfg.uri.ifBlank { "<default>" }} cluster=${cfg.cluster.ifBlank { "<none>" }} | TTS route uri=${ttsCfg.uri.ifBlank { "<default>" }} cluster=${ttsCfg.cluster.ifBlank { "<none>" }}"
        )
        if (!cfg.isValidForAsr()) {
            callbacks.onDebug(
                "${descriptor.displayName}: invalid ASR config appIdLen=${cfg.appId.length} tokenLen=${cfg.token.length} cluster=${cfg.cluster}"
            )
            return false
        }
        if (cfg.uri.contains("/api/v3", ignoreCase = true) || cfg.uri.contains("bigmodel", ignoreCase = true)) {
            callbacks.onDebug("${descriptor.displayName}: invalid ASR uri for streaming route -> ${cfg.uri}")
            return false
        }
        if (cfg.cluster.contains("tts", ignoreCase = true)) {
            callbacks.onDebug("${descriptor.displayName}: invalid ASR cluster contains tts -> ${cfg.cluster}")
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
                            "${descriptor.displayName}: speech below local threshold duration=${durationMs}ms frames=$utteranceFrameCount, still finishing to let sdk decide"
                        )
                    }
                    if (acceptedByFrames) {
                        callbacks.onDebug(
                            "${descriptor.displayName}: short speech accepted duration=${durationMs}ms frames=$utteranceFrameCount"
                        )
                    }
                    sendFinishTalkingDirective()
                }

                override fun onError(message: String) {
                    callbacks.onDebug("${descriptor.displayName}: mic error: $message")
                    stopListeningInternal(reason = "mic-error", emitDebug = false)
                    callbacks.onAsrError(-4, message)
                }
            }
        )
        if (stream == null) {
            callbacks.onDebug("${descriptor.displayName}: microphone start failed")
            return false
        }

        if (!startAsrEngine(request, cfg)) {
            microphone.stop()
            return false
        }

        listening = true
        ignoreEngineMessageUntilMs = 0L
        finishDirectiveSent = false
        emitPartialResults = request.partialResults
        captureDrainJob?.cancel()
        captureDrainJob = scope.launch {
            for (frame in stream) {
                if (!listening) {
                    break
                }
                synchronized(engineLock) {
                    val active = engine ?: return@synchronized
                    runCatching {
                        active.feedAudio(frame, frame.size)
                    }.onFailure { error ->
                        callbacks.onDebug(
                            "${descriptor.displayName}: feedAudio failed: ${error.message ?: "unknown"}"
                        )
                    }
                }
            }
        }

        callbacks.onAsrReady()
        callbacks.onDebug(
            "${descriptor.displayName}: listening locale=${request.locale} partial=${request.partialResults} ec=${request.audioProcessing.echoCancellation} ns=${request.audioProcessing.noiseSuppression} frameMs=$frameMs minMs=$minAcceptedSpeechMs minFrames=$minAcceptedSpeechFrames shortFrames=$shortSpeechAcceptFrames cluster=${cfg.cluster}"
        )
        return true
    }

    override fun stopListening(reason: String) {
        if (!listening) {
            callbacks.onDebug("${descriptor.displayName}: stop ignored by $reason")
            return
        }
        stopListeningInternal(reason = reason, emitDebug = true)
    }

    override fun speak(text: String, voiceId: String?) {
        callbacks.onDebug("${descriptor.displayName}: speak ignored (asr-only provider)")
    }

    override fun stopSpeak() {
        callbacks.onDebug("${descriptor.displayName}: stopSpeak ignored (asr-only provider)")
    }

    override fun setTtsVolume(volume: Float) {
        callbacks.onDebug("${descriptor.displayName}: setTtsVolume ignored (asr-only provider)")
    }

    override fun listVoices(): List<VoiceOption> = emptyList()

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
                    val end = min(offset + chunkSize, data.size)
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
        released = true
        stopListeningInternal(reason = "release", emitDebug = false)
        speaking = false
        microphone.release()
        output.release()
    }

    private fun maybePrepareEnvironment() {
        if (environmentPrepared) return
        environmentPrepared = true
        val app = appContext as? Application
        if (app == null) {
            callbacks.onDebug("${descriptor.displayName}: prepare environment skipped (application unavailable)")
            return
        }
        runCatching {
            SpeechEngineGenerator.PrepareEnvironment(appContext, app)
        }.onSuccess { prepared ->
            callbacks.onDebug("${descriptor.displayName}: sdk prepare environment=$prepared")
        }.onFailure { error ->
            callbacks.onDebug("${descriptor.displayName}: sdk prepare failed: ${error.message ?: "unknown"}")
        }
    }

    private fun startAsrEngine(request: SpeechListenRequest, cfg: VolcAsrConfig): Boolean {
        synchronized(engineLock) {
            val sdk = runCatching { SpeechEngineGenerator.getInstance() }
                .getOrElse { error ->
                    callbacks.onDebug("${descriptor.displayName}: getInstance failed: ${error.message ?: "unknown"}")
                    null
                }
            if (sdk == null) {
                return false
            }
            engine = sdk
            runCatching { sdk.setContext(appContext) }
            runCatching { sdk.setListener(speechListener) }

            callbacks.onDebug(
                "${descriptor.displayName}: init config route uri=${cfg.uri.ifBlank { "<default>" }} cluster=${cfg.cluster.ifBlank { "<none>" }} resourceId=${cfg.resourceId.ifBlank { "<none>" }} appIdLen=${cfg.appId.length} tokenLen=${cfg.token.length}"
            )
            applyBaseOptions(sdk, cfg)
            applyAsrOptions(sdk, request)

            val initCode = runCatching { sdk.initEngine() }
                .getOrElse { error ->
                    callbacks.onDebug("${descriptor.displayName}: init failed: ${error.message ?: "unknown"}")
                    Int.MIN_VALUE
                }
            if (initCode != 0) {
                callbacks.onDebug("${descriptor.displayName}: init engine code=$initCode (${humanReadableInitFailure(cfg, initCode)})")
                runCatching { sdk.destroyEngine() }
                engine = null
                return false
            }

            val startCode = runCatching {
                sdk.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, "")
            }.getOrElse { error ->
                callbacks.onDebug("${descriptor.displayName}: start engine failed: ${error.message ?: "unknown"}")
                Int.MIN_VALUE
            }
            if (startCode != 0) {
                callbacks.onDebug("${descriptor.displayName}: start engine code=$startCode")
                runCatching { sdk.destroyEngine() }
                engine = null
                return false
            }

            val talkingCode = runCatching {
                sdk.sendDirective(SpeechEngineDefines.DIRECTIVE_START_TALKING, "")
            }.getOrElse { error ->
                callbacks.onDebug("${descriptor.displayName}: start talking failed: ${error.message ?: "unknown"}")
                Int.MIN_VALUE
            }
            if (talkingCode != 0) {
                callbacks.onDebug("${descriptor.displayName}: start talking code=$talkingCode")
                runCatching { sdk.sendDirective(SpeechEngineDefines.DIRECTIVE_STOP_ENGINE, "") }
                runCatching { sdk.destroyEngine() }
                engine = null
                return false
            }
            return true
        }
    }

    private fun applyBaseOptions(engine: SpeechEngine, cfg: VolcAsrConfig) {
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
            SpeechEngineDefines.ASR_ENGINE
        )
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_AUTHENTICATE_TYPE_STRING,
            SpeechEngineDefines.AUTHENTICATE_TYPE_LATE_BIND
        )
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING,
            cfg.appId
        )
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING,
            cfg.token
        )
        if (cfg.cluster.isNotBlank()) {
            engine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_ASR_CLUSTER_STRING,
                cfg.cluster
            )
        }
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_UID_STRING,
            cfg.uid
        )
        if (cfg.resourceId.isNotBlank()) {
            engine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING,
                cfg.resourceId
            )
        }
        if (cfg.address.isNotBlank()) {
            engine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_ASR_ADDRESS_STRING,
                cfg.address
            )
        }
        if (cfg.uri.isNotBlank()) {
            engine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_ASR_URI_STRING,
                cfg.uri
            )
        }
        applyProtocolOptions(engine, cfg)
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrDefault("")
        if (androidId.isNotBlank()) {
            engine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_DEVICE_ID_STRING,
                androidId
            )
        }
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_LOG_LEVEL_STRING,
            SpeechEngineDefines.LOG_LEVEL_WARN
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_USE_ALOG_BOOL,
            true
        )
    }

    private fun applyProtocolOptions(engine: SpeechEngine, cfg: VolcAsrConfig) {
        val bigModelRoute = cfg.uri.contains("/api/v3/sauc/bigmodel", ignoreCase = true)
        if (!bigModelRoute) return
        runCatching {
            val defines = SpeechEngineDefines::class.java
            val key = defines.getField("PARAMS_KEY_PROTOCOL_TYPE_INT").get(null) as String
            val seed = (defines.getField("PROTOCOL_TYPE_SEED").get(null) as Number).toInt()
            engine.setOptionInt(key, seed)
            callbacks.onDebug("${descriptor.displayName}: protocol type set to PROTOCOL_TYPE_SEED for bigmodel route")
        }.onFailure { error ->
            callbacks.onDebug(
                "${descriptor.displayName}: protocol type seed not available in current SDK (${error.javaClass.simpleName})"
            )
        }
    }

    private fun applyAsrOptions(engine: SpeechEngine, request: SpeechListenRequest) {
        engine.setOptionInt(
            SpeechEngineDefines.PARAMS_KEY_SAMPLE_RATE_INT,
            16000
        )
        engine.setOptionInt(
            SpeechEngineDefines.PARAMS_KEY_CHANNEL_NUM_INT,
            1
        )
        engine.setOptionInt(
            SpeechEngineDefines.PARAMS_KEY_ASR_SCENARIO_INT,
            SpeechEngineDefines.ASR_SCENARIO_STREAMING
        )
        engine.setOptionInt(
            SpeechEngineDefines.PARAMS_KEY_ASR_WORK_MODE_INT,
            SpeechEngineDefines.ASR_WORK_MODE_ONLINE
        )
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_ASR_RESULT_TYPE_STRING,
            SpeechEngineDefines.ASR_RESULT_TYPE_FULL
        )
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_ASR_LANGUAGE_STRING,
            request.locale.ifBlank { "zh-CN" }
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ENABLE_GET_VOLUME_BOOL,
            true
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ASR_SHOW_VOLUME_BOOL,
            true
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ASR_SHOW_UTTER_BOOL,
            true
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ASR_AUTO_STOP_BOOL,
            false
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ASR_ENABLE_ITN_BOOL,
            true
        )
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ASR_SHOW_PUNC_BOOL,
            true
        )
    }

    private fun sendFinishTalkingDirective() {
        synchronized(engineLock) {
            if (!listening || finishDirectiveSent) {
                return
            }
            val active = engine ?: return
            val code = runCatching {
                active.sendDirective(SpeechEngineDefines.DIRECTIVE_FINISH_TALKING, "")
            }.getOrElse { error ->
                callbacks.onDebug("${descriptor.displayName}: finish talking failed: ${error.message ?: "unknown"}")
                Int.MIN_VALUE
            }
            finishDirectiveSent = true
            callbacks.onDebug("${descriptor.displayName}: finish talking code=$code")
        }
    }

    private fun stopListeningInternal(reason: String, emitDebug: Boolean) {
        val wasListening = listening
        listening = false
        utteranceActive = false
        utteranceSuppressed = false
        utteranceFrameCount = 0
        ignoreEngineMessageUntilMs = System.currentTimeMillis() + 900L
        captureDrainJob?.cancel()
        captureDrainJob = null
        microphone.stop()

        synchronized(engineLock) {
            val active = engine
            if (active != null) {
                if (!finishDirectiveSent) {
                    runCatching {
                        active.sendDirective(SpeechEngineDefines.DIRECTIVE_FINISH_TALKING, "")
                    }
                }
                runCatching {
                    active.sendDirective(SpeechEngineDefines.DIRECTIVE_STOP_ENGINE, "")
                }
                runCatching { active.destroyEngine() }
            }
            engine = null
            finishDirectiveSent = false
        }

        if (emitDebug && wasListening) {
            callbacks.onDebug("${descriptor.displayName}: listening stopped by $reason")
        }
    }

    private fun handleEngineMessage(messageType: Int, data: ByteArray?, dataLen: Int) {
        if (released) return
        val now = System.currentTimeMillis()
        if (!listening && now < ignoreEngineMessageUntilMs) {
            return
        }
        val payload = fetchEnginePayload(messageType).ifBlank {
            decodePayload(data, dataLen)
        }
        when (messageType) {
            SpeechEngineDefines.MESSAGE_TYPE_PARTIAL_RESULT,
            SpeechEngineDefines.MESSAGE_TYPE_ALL_PARTIAL_RESULT -> {
                if (!emitPartialResults) return
                val text = extractText(payload)
                if (text.isNotBlank()) {
                    callbacks.onAsrResult(text, false, extractConfidence(payload))
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_FINAL_RESULT -> {
                val text = extractText(payload)
                if (text.isBlank()) {
                    callbacks.onAsrError(-5, "no match")
                } else {
                    callbacks.onAsrResult(text, true, extractConfidence(payload))
                }
                stopListeningInternal(reason = "sdk-final", emitDebug = false)
            }

            SpeechEngineDefines.MESSAGE_TYPE_VOLUME_LEVEL -> {
                val level = extractVolume(payload)
                if (level != null) {
                    callbacks.onSpeechDetected(level)
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                val code = extractStatusCode(payload) ?: -6
                val message = extractMessage(payload).ifBlank {
                    "volcengine engine error"
                }
                callbacks.onDebug(
                    "${descriptor.displayName}: engine error code=$code payload=${payload.take(140)}"
                )
                callbacks.onAsrError(code, message)
                stopListeningInternal(reason = "sdk-error", emitDebug = false)
            }
        }
    }

    private fun fetchEnginePayload(messageType: Int): String {
        synchronized(engineLock) {
            val active = engine ?: return ""
            return runCatching {
                active.fetchResult(messageType).orEmpty()
            }.getOrElse { error ->
                callbacks.onDebug("${descriptor.displayName}: fetchResult failed: ${error.message ?: "unknown"}")
                ""
            }
        }
    }

    private fun decodePayload(data: ByteArray?, len: Int): String {
        if (data == null || len <= 0) return ""
        return runCatching {
            String(data, 0, min(len, data.size), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun extractText(payload: String): String {
        val root = parsePayload(payload) ?: return ""
        val preferred = linkedSetOf("text", "result_text", "utterance_text")
        return findFirstString(root, preferred)?.trim().orEmpty()
    }

    private fun extractConfidence(payload: String): Float? {
        val root = parsePayload(payload) ?: return null
        return findFirstNumber(root, linkedSetOf("confidence", "conf"))?.coerceIn(0f, 1f)
    }

    private fun extractVolume(payload: String): Float? {
        val root = parsePayload(payload) ?: return null
        val raw = findFirstNumber(root, linkedSetOf("volume", "volume_level", "level")) ?: return null
        return if (raw > 1f) {
            (raw / 100f).coerceIn(0f, 1f)
        } else {
            raw.coerceIn(0f, 1f)
        }
    }

    private fun extractStatusCode(payload: String): Int? {
        val root = parsePayload(payload) ?: return null
        return findFirstNumber(root, linkedSetOf("status_code", "error_code", "code", "err_no"))?.toInt()
    }

    private fun extractMessage(payload: String): String {
        val root = parsePayload(payload) ?: return ""
        return findFirstString(root, linkedSetOf("message", "msg", "error", "err_msg")).orEmpty()
    }

    private fun parsePayload(payload: String): JsonElement? {
        if (payload.isBlank()) return null
        return runCatching {
            JsonParser.parseString(payload)
        }.getOrNull()
    }

    private fun findFirstString(element: JsonElement, preferredKeys: Set<String>): String? {
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            if (primitive.isString) {
                val value = primitive.asString
                if (value.isNotBlank()) return value
            }
            return null
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val nested = findFirstString(child, preferredKeys)
                if (!nested.isNullOrBlank()) return nested
            }
            return null
        }
        val obj = element.asJsonObject
        for (key in preferredKeys) {
            val value = obj.get(key)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val text = value.asString
                if (text.isNotBlank()) return text
            }
        }
        for ((_, value) in obj.entrySet()) {
            val nested = findFirstString(value, preferredKeys)
            if (!nested.isNullOrBlank()) return nested
        }
        return null
    }

    private fun findFirstNumber(element: JsonElement, preferredKeys: Set<String>): Float? {
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            if (primitive.isNumber) {
                return primitive.asFloat
            }
            return null
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val nested = findFirstNumber(child, preferredKeys)
                if (nested != null) return nested
            }
            return null
        }
        val obj = element.asJsonObject
        for (key in preferredKeys) {
            val value = obj.get(key)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                return value.asFloat
            }
        }
        for ((_, value) in obj.entrySet()) {
            val nested = findFirstNumber(value, preferredKeys)
            if (nested != null) return nested
        }
        return null
    }

    private fun runtimeConfig(): Pair<VolcAsrConfig, List<String>> {
        val (cfg, warnings) = VolcConfigLoader.loadAsrConfig()
        val fallbackUid = cfg.uid.ifBlank { "versementor-${android.os.Build.MODEL}" }
        return cfg.copy(uid = fallbackUid) to warnings
    }

    private fun VolcAsrConfig.isValidForAsr(): Boolean {
        return appId.isNotBlank() && token.isNotBlank() && cluster.isNotBlank()
    }

    private fun humanReadableInitFailure(cfg: VolcAsrConfig, initCode: Int): String {
        return when {
            cfg.appId.isBlank() || cfg.token.isBlank() -> "missing appId/token"
            cfg.cluster.isBlank() -> "missing asr cluster"
            cfg.uri.contains("/api/v3", ignoreCase = true) || cfg.uri.contains("bigmodel", ignoreCase = true) ->
                "ASR uses TTS/bigmodel uri; use streaming ASR uri like /api/v2/asr"
            cfg.cluster.contains("tts", ignoreCase = true) -> "ASR cluster looks like TTS cluster"
            initCode == Int.MIN_VALUE -> "SDK threw exception during init"
            else -> "check route/cluster/app credentials"
        }
    }
}
