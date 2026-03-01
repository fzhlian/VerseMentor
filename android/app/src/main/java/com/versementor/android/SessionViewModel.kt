package com.versementor.android

import android.app.Application
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.versementor.android.bridge.LocalKotlinBridge
import com.versementor.android.bridge.SessionBridge
import com.versementor.android.bridge.SharedCoreBridge
import com.versementor.android.bridge.sharedcore.SharedCoreCodec
import com.versementor.android.bridge.sharedcore.LocalBridgeSharedCoreRuntime
import com.versementor.android.bridge.sharedcore.SharedCoreRuntimeHooks
import com.versementor.android.net.HttpClient
import com.versementor.android.net.OkHttpClientImpl
import com.versementor.android.session.Poem
import com.versementor.android.session.SamplePoems
import com.versementor.android.session.SessionAction
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionState
import com.versementor.android.session.SessionStateType
import com.versementor.android.session.SessionUiState
import com.versementor.android.session.buildDefaultConfig
import com.versementor.android.session.buildInitialSession
import com.versementor.android.speech.SpeechIO
import com.versementor.android.storage.PoemLineVariant
import com.versementor.android.storage.PoemVariants
import com.versementor.android.storage.PoemVariantsCacheEntry
import com.versementor.android.storage.PreferenceStore
import com.versementor.android.storage.SharedPrefsVariantCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val MIN_VARIANT_TTL_DAYS = 1
        const val MAX_VARIANT_TTL_DAYS = 365
        const val DEFAULT_TRANSIENT_ASR_PROMPT_THRESHOLD = 3
        const val DEFAULT_TRANSIENT_ASR_RETRY_DELAY_MS = 350
        const val DEFAULT_ASR_STOP_TO_START_COOLDOWN_MS = 220
        const val MIN_TRANSIENT_ASR_PROMPT_THRESHOLD = 1
        const val MAX_TRANSIENT_ASR_PROMPT_THRESHOLD = 10
        const val MIN_TRANSIENT_ASR_RETRY_DELAY_MS = 100
        const val MAX_TRANSIENT_ASR_RETRY_DELAY_MS = 2000
        const val MIN_ASR_STOP_TO_START_COOLDOWN_MS = 80
        const val MAX_ASR_STOP_TO_START_COOLDOWN_MS = 1200
        private const val MAX_VARIANTS_PER_LINE = 4
        private const val START_NOT_READY_LOG_EVERY = 4
        private const val START_NOT_READY_FORCE_STOP_THRESHOLD = 8
        private const val START_NOT_READY_BACKOFF_DELAY_MS = 700
    }

    private val prefs = PreferenceStore(app)
    private val variantCacheStore = SharedPrefsVariantCacheStore(prefs)
    private val httpClient: HttpClient = OkHttpClientImpl()
    private val speech = SpeechIO(app)
    private val sharedCoreCodec = SharedCoreCodec()
    private val localBridgeRuntime = LocalBridgeSharedCoreRuntime(codec = sharedCoreCodec)
    private val variantApiEndpoint: String = BuildConfig.VARIANT_API_ENDPOINT.trim().trimEnd('/')
    private val sessionBridge: SessionBridge =
        if (BuildConfig.USE_SHARED_CORE_REDUCER) SharedCoreBridge() else LocalKotlinBridge()
    private var sharedCoreHookToken: Long? = null
    private var isSpeaking = false
    private var pendingStartListening = false
    private var isManuallyPaused = false
    private var consecutiveTransientAsrErrors = 0
    private var consecutiveStartNotReady = 0
    private var transientRetryJob: Job? = null

    var uiState: SessionUiState by mutableStateOf(SessionUiState())
        private set

    var settings: SettingsState by mutableStateOf(SettingsState())
        private set

    var debugCheckResult: String by mutableStateOf("-")
        private set

    var runtimeCheckResult: String by mutableStateOf("-")
        private set

    var codecCheckResult: String by mutableStateOf("-")
        private set

    var eventCheckResult: String by mutableStateOf("-")
        private set

    var allBridgeCheckResult: String by mutableStateOf("-")
        private set

    private var state: SessionState = buildInitialSession(
        config = buildDefaultConfig(prefs),
        poems = SamplePoems.poems
    )

    init {
        if (BuildConfig.USE_SHARED_CORE_REDUCER) {
            sharedCoreHookToken = SharedCoreRuntimeHooks.registerReduceHookIfAbsent(localBridgeRuntime::reduce)
        }
        speech.onAsrResult = { text, isFinal, confidence ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!uiState.sessionActive || uiState.sessionPaused) {
                    return@launch
                }
                transientRetryJob?.cancel()
                transientRetryJob = null
                consecutiveTransientAsrErrors = 0
                consecutiveStartNotReady = 0
                val heard = text.trim()
                val nextRecognizedLines = if (isFinal && heard.isNotEmpty() && uiState.recognizedLines.lastOrNull() != heard) {
                    uiState.recognizedLines + heard
                } else {
                    uiState.recognizedLines
                }
                uiState = uiState.copy(
                    liveHeard = heard,
                    lastHeard = heard,
                    awaitingSpeech = false,
                    recognizedLines = nextRecognizedLines
                )
                dispatch(SessionEvent.UserAsr(text, isFinal, confidence, System.currentTimeMillis()))
            }
        }
        speech.onAsrError = { code, message ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!uiState.sessionActive || uiState.sessionPaused) {
                    return@launch
                }
                consecutiveStartNotReady = 0
                if (
                    code == SpeechIO.ERROR_SERVICE_UNAVAILABLE ||
                    code == SpeechIO.ERROR_START_LISTENING_FAILED
                ) {
                    transientRetryJob?.cancel()
                    transientRetryJob = null
                    consecutiveTransientAsrErrors = 0
                    pendingStartListening = false
                    isManuallyPaused = true
                    speech.stopListening(reason = "asr-error-infra")
                    uiState = uiState.copy(
                        sessionPaused = true,
                        awaitingSpeech = false,
                        statusText = message,
                        logs = appendLog("ASR infrastructure error($code): $message")
                    )
                    return@launch
                }
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    transientRetryJob?.cancel()
                    transientRetryJob = null
                    consecutiveTransientAsrErrors = 0
                    pendingStartListening = false
                    isManuallyPaused = true
                    speech.stopListening(reason = "asr-error-permission")
                    uiState = uiState.copy(
                        sessionPaused = true,
                        awaitingSpeech = false,
                        statusText = text(R.string.permission_mic),
                        logs = appendLog("ASR permission error: $message")
                    )
                    return@launch
                }
                if (isTransientAsrError(code)) {
                    consecutiveTransientAsrErrors += 1
                    pendingStartListening = false
                    uiState = uiState.copy(
                        awaitingSpeech = false,
                        logs = appendLog("ASR transient($code)x$consecutiveTransientAsrErrors: $message")
                    )
                    if (consecutiveTransientAsrErrors >= settings.transientAsrPromptThreshold) {
                        transientRetryJob?.cancel()
                        transientRetryJob = null
                        consecutiveTransientAsrErrors = 0
                        dispatch(SessionEvent.UserAsrError(code, message))
                        return@launch
                    }
                    scheduleTransientAsrRetry()
                    return@launch
                }
                transientRetryJob?.cancel()
                transientRetryJob = null
                consecutiveTransientAsrErrors = 0
                pendingStartListening = false
                uiState = uiState.copy(
                    awaitingSpeech = false,
                    logs = appendLog("ASR error($code): $message")
                )
                dispatch(SessionEvent.UserAsrError(code, message))
            }
        }
        speech.onAsrDebug = { message ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!uiState.sessionActive) {
                    return@launch
                }
                uiState = uiState.copy(
                    logs = appendLog("ASR debug: $message")
                )
            }
        }
        speech.onSpeaking = { speaking ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!uiState.sessionActive) {
                    return@launch
                }
                isSpeaking = speaking
                if (speaking) {
                    uiState = uiState.copy(
                        statusText = text(R.string.status_speaking),
                        awaitingSpeech = false
                    )
                } else if (pendingStartListening && uiState.sessionActive && !uiState.sessionPaused && !isManuallyPaused) {
                    pendingStartListening = false
                    beginListening()
                } else if (uiState.sessionPaused || isManuallyPaused) {
                    uiState = uiState.copy(
                        statusText = text(R.string.status_paused),
                        awaitingSpeech = false
                    )
                } else {
                    uiState = uiState.copy(
                        statusText = resolveStatusText(state.type, uiState.awaitingSpeech)
                    )
                }
            }
        }
        loadSettings()
        refreshVoices()
        uiState = uiState.copy(
            statusText = resolveStatusText(SessionStateType.IDLE, awaitingSpeech = false)
        )
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (uiState.sessionActive && !uiState.sessionPaused) {
                    dispatch(SessionEvent.Tick(System.currentTimeMillis()))
                }
            }
        }
    }

    fun onHomeButtonTap() {
        if (!uiState.sessionActive) {
            startSession()
            return
        }
        if (uiState.sessionPaused) {
            resumeSessionListening()
        } else {
            pauseSessionListening()
        }
    }

    fun onHomeButtonLongPress() {
        if (uiState.sessionActive && !uiState.sessionPaused) {
            stopSession(stopSpeak = true)
        }
    }

    fun startSession() {
        if (uiState.sessionActive) return
        resetStateMachine()
        if (state.type != SessionStateType.IDLE) {
            resetStateMachine()
        }
        isSpeaking = false
        pendingStartListening = false
        isManuallyPaused = false
        consecutiveTransientAsrErrors = 0
        consecutiveStartNotReady = 0
        transientRetryJob?.cancel()
        transientRetryJob = null
        uiState = SessionUiState(
            statusText = resolveStatusText(SessionStateType.IDLE, awaitingSpeech = false),
            sessionActive = true,
            sessionPaused = false,
            logs = uiState.logs
        )
        dispatch(SessionEvent.UserUiStart(System.currentTimeMillis()))
    }

    fun dispatch(event: SessionEvent) {
        val previousType = state.type
        val output = sessionBridge.reduce(state, event)
        state = output.state
        handleActions(output.actions)
        if (previousType != SessionStateType.EXIT && state.type == SessionStateType.EXIT) {
            stopSession(stopSpeak = false)
            return
        }
        uiState = uiState.copy(
            statusText = resolveStatusText(state.type, uiState.awaitingSpeech)
        )
    }

    private fun handleActions(actions: List<SessionAction>) {
        actions.forEach { action ->
            when (action) {
                is SessionAction.Speak -> {
                    isSpeaking = true
                    pendingStartListening = false
                    uiState = uiState.copy(
                        lastSpoken = action.text,
                        awaitingSpeech = false,
                        logs = appendLog("TTS: ${action.text}")
                    )
                    speech.stopListening(reason = "before-tts-speak")
                    speech.speak(action.text, settings.ttsVoiceId)
                }

                SessionAction.StartListening -> {
                    if (!uiState.sessionActive) {
                        // Ignore reducer request when the control button is in "start" mode.
                    } else if (uiState.sessionPaused || isManuallyPaused) {
                        pendingStartListening = true
                        uiState = uiState.copy(
                            statusText = text(R.string.status_paused),
                            awaitingSpeech = false,
                            sessionPaused = true
                        )
                    } else if (isSpeaking) {
                        pendingStartListening = true
                    } else {
                        beginListening()
                    }
                }

                SessionAction.StopListening -> {
                    pendingStartListening = false
                    consecutiveStartNotReady = 0
                    uiState = uiState.copy(awaitingSpeech = false)
                    speech.stopListening(reason = "session-action-stop")
                }

                is SessionAction.UpdateScreenHint -> {
                    uiState = uiState.copy(logs = appendLog("Hint: ${action.key}"))
                }

                is SessionAction.FetchVariants -> {
                    fetchVariants(action.poem)
                }
            }
        }
    }

    private fun pauseSessionListening() {
        isManuallyPaused = true
        pendingStartListening = false
        transientRetryJob?.cancel()
        transientRetryJob = null
        consecutiveStartNotReady = 0
        speech.stopListening(reason = "pause-session")
        uiState = uiState.copy(
            sessionPaused = true,
            awaitingSpeech = false,
            statusText = text(R.string.status_paused)
        )
    }

    private fun resumeSessionListening() {
        isManuallyPaused = false
        uiState = uiState.copy(sessionPaused = false)
        if (!uiState.sessionActive || state.type == SessionStateType.IDLE || state.type == SessionStateType.EXIT) {
            uiState = uiState.copy(statusText = resolveStatusText(state.type, awaitingSpeech = false))
            return
        }
        if (isSpeaking) {
            pendingStartListening = true
        } else {
            pendingStartListening = false
            beginListening()
        }
    }

    private fun stopSession(stopSpeak: Boolean) {
        pendingStartListening = false
        isManuallyPaused = false
        isSpeaking = false
        consecutiveTransientAsrErrors = 0
        consecutiveStartNotReady = 0
        transientRetryJob?.cancel()
        transientRetryJob = null
        speech.stopListening(reason = "stop-session")
        if (stopSpeak) {
            speech.stopSpeak()
        }
        resetStateMachine()
        uiState = SessionUiState(
            statusText = resolveStatusText(SessionStateType.IDLE, awaitingSpeech = false),
            sessionActive = false,
            sessionPaused = false,
            logs = uiState.logs
        )
    }

    private fun beginListening() {
        if (uiState.awaitingSpeech) {
            return
        }
        val started = speech.startListening()
        if (started) {
            consecutiveStartNotReady = 0
            uiState = uiState.copy(
                statusText = text(R.string.waiting_input),
                awaitingSpeech = true,
                liveHeard = ""
            )
            return
        }
        if (speech.consumeStartFailureHandled()) {
            // startListening already emitted a concrete ASR error (infra/permission/etc.),
            // so this is not a "not ready" case and should not enter retry/backoff logs.
            consecutiveStartNotReady = 0
            return
        }
        consecutiveStartNotReady += 1
        val retryCount = consecutiveStartNotReady
        val shouldForceStop = retryCount >= START_NOT_READY_FORCE_STOP_THRESHOLD
        if (shouldForceStop) {
            speech.stopListening(reason = "start-not-ready-force-stop")
        }
        val retryLog = when {
            retryCount <= 3 -> "ASR start not ready x$retryCount, schedule retry"
            retryCount % START_NOT_READY_LOG_EVERY == 0 -> {
                if (shouldForceStop) {
                    "ASR start not ready x$retryCount, force stop and backoff retry"
                } else {
                    "ASR start still not ready x$retryCount, schedule retry"
                }
            }
            else -> null
        }
        uiState = if (retryLog != null) {
            uiState.copy(
                awaitingSpeech = false,
                statusText = resolveStatusText(state.type, awaitingSpeech = false),
                logs = appendLog(retryLog)
            )
        } else {
            uiState.copy(
                awaitingSpeech = false,
                statusText = resolveStatusText(state.type, awaitingSpeech = false)
            )
        }
        if (!uiState.sessionActive || uiState.sessionPaused || isManuallyPaused || isSpeaking) {
            return
        }
        val retryDelayMs = when {
            shouldForceStop -> START_NOT_READY_BACKOFF_DELAY_MS
            retryCount >= START_NOT_READY_LOG_EVERY -> settings.transientAsrRetryDelayMs.coerceAtLeast(350)
            else -> settings.transientAsrRetryDelayMs.coerceAtMost(200)
        }
        scheduleTransientAsrRetry(
            delayMs = retryDelayMs
        )
    }

    private fun resetStateMachine() {
        state = buildInitialSession(
            config = state.ctx.config,
            poems = state.ctx.poems
        )
    }

    private fun fetchVariants(poem: Poem) {
        if (!state.ctx.config.variants.enableOnline) {
            viewModelScope.launch(Dispatchers.Main) {
                uiState = uiState.copy(logs = appendLog("Variants disabled, skip ${poem.title}"))
                dispatch(SessionEvent.VariantsFetched(null))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cacheKey = poem.id

            val resolved = runCatching {
                val cached = variantCacheStore.get(cacheKey)
                if (cached != null && cached.expiresAt > now) {
                    cached to "cache"
                } else {
                    val onlineEntry = fetchOnlineVariantEntry(poem, now)
                    val nextEntry = onlineEntry ?: buildLocalVariantEntry(poem, now)
                    val source = if (onlineEntry == null) "local-default" else "online"
                    variantCacheStore.set(cacheKey, nextEntry)
                    nextEntry to source
                }
            }.getOrNull()

            withContext(Dispatchers.Main.immediate) {
                if (resolved == null) {
                    uiState = uiState.copy(logs = appendLog("Variants fetch failed for ${poem.title}"))
                    dispatch(SessionEvent.VariantsFetched(null))
                } else {
                    val (resolvedEntry, source) = resolved
                    uiState = uiState.copy(logs = appendLog("Variants ready (${source}) for ${poem.title}"))
                    dispatch(SessionEvent.VariantsFetched(resolvedEntry))
                }
            }
        }
    }

    private suspend fun fetchOnlineVariantEntry(poem: Poem, now: Long): PoemVariantsCacheEntry? {
        if (variantApiEndpoint.isBlank()) return null

        val url = buildVariantApiUrl(poem)
        val response = runCatching {
            httpClient.getJson(url, VariantProviderResponse::class.java)
        }.getOrNull() ?: return null

        val lines = response.lines
            .orEmpty()
            .mapNotNull { line ->
                val idx = line.lineIndex ?: return@mapNotNull null
                val texts = line.variants
                    .orEmpty()
                    .mapNotNull { candidate ->
                        candidate.text
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?.let { text -> text to (candidate.confidence ?: 0.6) }
                    }
                    .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                    .map { it.first }
                    .distinct()
                    .take(MAX_VARIANTS_PER_LINE)
                if (texts.isEmpty()) null else PoemLineVariant(lineIndex = idx, variants = texts)
            }
            .sortedBy { it.lineIndex }

        val sourceTag = response.provider?.trim()?.takeIf(String::isNotEmpty) ?: "http"

        return PoemVariantsCacheEntry(
            poemId = poem.id,
            variants = PoemVariants(
                poemId = poem.id,
                lines = lines,
                sourceTags = listOf(sourceTag)
            ),
            cachedAt = now,
            expiresAt = now + computeVariantTtlMs()
        )
    }

    private fun buildVariantApiUrl(poem: Poem): String {
        val query = listOf(
            "title" to poem.title,
            "author" to poem.author,
            "dynasty" to poem.dynasty
        ).joinToString("&") { (k, v) ->
            "${Uri.encode(k)}=${Uri.encode(v)}"
        }
        return "${variantApiEndpoint}?${query}"
    }

    private fun computeVariantTtlMs(): Long {
        return state.ctx.config.variants.ttlDays
            .coerceIn(MIN_VARIANT_TTL_DAYS, MAX_VARIANT_TTL_DAYS)
            .toLong() * 24L * 60L * 60L * 1000L
    }

    private fun buildLocalVariantEntry(poem: Poem, now: Long): PoemVariantsCacheEntry {
        val variants = PoemVariants(
            poemId = poem.id,
            lines = poem.lines.mapIndexed { idx, line ->
                PoemLineVariant(lineIndex = idx, variants = listOf(line.text))
            },
            sourceTags = listOf("local-default")
        )

        return PoemVariantsCacheEntry(
            poemId = poem.id,
            variants = variants,
            cachedAt = now,
            expiresAt = now + computeVariantTtlMs()
        )
    }

    private fun loadSettings() {
        val loaded = prefs.loadSettings()
        val normalized = loaded.copy(
            transientAsrPromptThreshold = loaded.transientAsrPromptThreshold.coerceIn(
                MIN_TRANSIENT_ASR_PROMPT_THRESHOLD,
                MAX_TRANSIENT_ASR_PROMPT_THRESHOLD
            ),
            transientAsrRetryDelayMs = loaded.transientAsrRetryDelayMs.coerceIn(
                MIN_TRANSIENT_ASR_RETRY_DELAY_MS,
                MAX_TRANSIENT_ASR_RETRY_DELAY_MS
            ),
            asrStopToStartCooldownMs = loaded.asrStopToStartCooldownMs.coerceIn(
                MIN_ASR_STOP_TO_START_COOLDOWN_MS,
                MAX_ASR_STOP_TO_START_COOLDOWN_MS
            )
        )
        settings = normalized
        applyAsrTuningToSpeech(normalized)
        if (normalized != loaded) {
            prefs.saveSettings(normalized)
        }
        syncStateConfig()
    }

    private fun refreshVoices() {
        viewModelScope.launch(Dispatchers.IO) {
            val voices = speech.listVoices()
            withContext(Dispatchers.Main) {
                settings = settings.copy(ttsVoices = voices)
            }
        }
    }

    fun setFollowSystem(follow: Boolean) {
        settings = settings.copy(followSystem = follow)
        prefs.saveSettings(settings)
        syncStateConfig()
        val locales = if (follow) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags("zh")
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setTtsVoice(id: String, name: String) {
        settings = settings.copy(ttsVoiceId = id, ttsVoiceName = name)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setAccentTolerance(
        anAng: Boolean? = null,
        enEng: Boolean? = null,
        inIng: Boolean? = null,
        ianIang: Boolean? = null
    ) {
        val current = settings.accentTolerance
        settings = settings.copy(
            accentTolerance = current.copy(
                anAng = anAng ?: current.anAng,
                enEng = enEng ?: current.enEng,
                inIng = inIng ?: current.inIng,
                ianIang = ianIang ?: current.ianIang
            )
        )
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setToneRemind(enabled: Boolean) {
        settings = settings.copy(toneRemind = enabled)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setVariantsEnable(enabled: Boolean) {
        settings = settings.copy(variantsEnable = enabled)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setVariantTtl(days: Int) {
        val normalized = days.coerceIn(MIN_VARIANT_TTL_DAYS, MAX_VARIANT_TTL_DAYS)
        settings = settings.copy(variantTtlDays = normalized)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setTransientAsrPromptThreshold(count: Int) {
        val normalized = count.coerceIn(MIN_TRANSIENT_ASR_PROMPT_THRESHOLD, MAX_TRANSIENT_ASR_PROMPT_THRESHOLD)
        if (normalized == settings.transientAsrPromptThreshold) return
        settings = settings.copy(transientAsrPromptThreshold = normalized)
        prefs.saveSettings(settings)
        uiState = uiState.copy(logs = appendLog("Debug: ASR prompt threshold -> $normalized"))
    }

    fun setTransientAsrRetryDelayMs(delayMs: Int) {
        val normalized = delayMs.coerceIn(MIN_TRANSIENT_ASR_RETRY_DELAY_MS, MAX_TRANSIENT_ASR_RETRY_DELAY_MS)
        if (normalized == settings.transientAsrRetryDelayMs) return
        settings = settings.copy(transientAsrRetryDelayMs = normalized)
        prefs.saveSettings(settings)
        uiState = uiState.copy(logs = appendLog("Debug: ASR retry delay -> ${normalized}ms"))
    }

    fun setAsrStopToStartCooldownMs(delayMs: Int) {
        val normalized = delayMs.coerceIn(MIN_ASR_STOP_TO_START_COOLDOWN_MS, MAX_ASR_STOP_TO_START_COOLDOWN_MS)
        if (normalized == settings.asrStopToStartCooldownMs) return
        settings = settings.copy(asrStopToStartCooldownMs = normalized)
        prefs.saveSettings(settings)
        applyAsrTuningToSpeech()
        uiState = uiState.copy(logs = appendLog("Debug: ASR stop-start cooldown -> ${normalized}ms"))
    }

    fun resetTransientAsrTuning() {
        if (
            settings.transientAsrPromptThreshold == DEFAULT_TRANSIENT_ASR_PROMPT_THRESHOLD &&
            settings.transientAsrRetryDelayMs == DEFAULT_TRANSIENT_ASR_RETRY_DELAY_MS &&
            settings.asrStopToStartCooldownMs == DEFAULT_ASR_STOP_TO_START_COOLDOWN_MS
        ) return
        settings = settings.copy(
            transientAsrPromptThreshold = DEFAULT_TRANSIENT_ASR_PROMPT_THRESHOLD,
            transientAsrRetryDelayMs = DEFAULT_TRANSIENT_ASR_RETRY_DELAY_MS,
            asrStopToStartCooldownMs = DEFAULT_ASR_STOP_TO_START_COOLDOWN_MS
        )
        prefs.saveSettings(settings)
        applyAsrTuningToSpeech()
        uiState = uiState.copy(
            logs = appendLog(
                "Debug: ASR tuning reset -> threshold=$DEFAULT_TRANSIENT_ASR_PROMPT_THRESHOLD, delay=${DEFAULT_TRANSIENT_ASR_RETRY_DELAY_MS}ms, cooldown=${DEFAULT_ASR_STOP_TO_START_COOLDOWN_MS}ms"
            )
        )
    }

    fun getAsrLogs(): List<String> {
        return uiState.logs.filter(::isAsrLogLine)
    }

    fun getAsrLogText(): String {
        return getAsrLogs().joinToString(separator = "\n")
    }

    fun hasCapturedAudio(): Boolean {
        return speech.hasCapturedAudio() && !speech.isCapturePlaybackActive()
    }

    fun replayCapturedAudio() {
        val replayed = speech.playCapturedAudio()
        val replayLog = when {
            replayed -> "ASR debug: capture replay requested by app"
            speech.isCapturePlaybackActive() -> "ASR debug: capture replay busy"
            else -> "ASR debug: capture replay unavailable"
        }
        uiState = uiState.copy(
            logs = appendLog(replayLog)
        )
    }

    fun clearAsrLogs() {
        val retained = uiState.logs.filterNot(::isAsrLogLine)
        uiState = uiState.copy(logs = retained)
    }

    fun clearVariantCache() {
        prefs.clearVariantCache()
    }

    fun addDynastyAlias(alias: String, canonical: String) {
        if (alias.isBlank() || canonical.isBlank()) return
        val updated = settings.dynastyMappings + "${alias.trim()} -> ${canonical.trim()}"
        settings = settings.copy(dynastyMappings = updated)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun addDynastyGroup(alias: String, ids: String) {
        if (alias.isBlank() || ids.isBlank()) return
        val updated = settings.dynastyMappings + "${alias.trim()} => ${ids.trim()}"
        settings = settings.copy(dynastyMappings = updated)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun addAuthor(name: String, alias: String) {
        if (name.isBlank()) return
        val updated = settings.authors + if (alias.isBlank()) name.trim() else "${name.trim()} (${alias.trim()})"
        settings = settings.copy(authors = updated)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    private fun syncStateConfig() {
        val config = com.versementor.android.session.AppConfig(
            toneRemind = settings.toneRemind,
            accentTolerance = settings.accentTolerance,
            variants = com.versementor.android.session.VariantFetchConfig(
                enableOnline = settings.variantsEnable,
                ttlDays = settings.variantTtlDays
            ),
            timeouts = state.ctx.config.timeouts,
            recite = state.ctx.config.recite
        )
        state = state.copy(ctx = state.ctx.copy(config = config))
    }

    fun runAsrErrorFlowCheck() {
        val base = buildInitialSession(
            config = state.ctx.config,
            poems = state.ctx.poems
        )
        val started = sessionBridge.reduce(base, SessionEvent.UserUiStart(System.currentTimeMillis()))
        val checked = sessionBridge.reduce(started.state, SessionEvent.UserAsrError(7, "no match"))
        val hasSpeak = checked.actions.any { it is SessionAction.Speak }
        val hasStartListening = checked.actions.any { it is SessionAction.StartListening }
        val actionTypes = checked.actions.joinToString("->") { action ->
            when (action) {
                is SessionAction.Speak -> "SPEAK"
                SessionAction.StartListening -> "START_LISTENING"
                SessionAction.StopListening -> "STOP_LISTENING"
                is SessionAction.UpdateScreenHint -> "UPDATE_SCREEN_HINT"
                is SessionAction.FetchVariants -> "FETCH_VARIANTS"
            }
        }.ifBlank { "none" }
        val trace = (sessionBridge as? SharedCoreBridge)?.getLastTrace()
        val mode = if (BuildConfig.USE_SHARED_CORE_REDUCER) {
            if (trace != null) "shared-core-bridge:${trace.path}/${trace.reason}" else "shared-core-bridge"
        } else {
            "local-kotlin"
        }
        val runtimePathOk = !BuildConfig.USE_SHARED_CORE_REDUCER || trace?.path == "runtime"
        val pass = hasSpeak && hasStartListening && checked.state.type == started.state.type && runtimePathOk
        debugCheckResult = "AsrErrorCheck:${if (pass) "PASS" else "FAIL"}(${mode},${checked.state.type},$actionTypes)"
        uiState = uiState.copy(logs = appendLog("Debug: $debugCheckResult"))
    }

    fun runRuntimePathCheck() {
        if (!BuildConfig.USE_SHARED_CORE_REDUCER) {
            runtimeCheckResult = "RuntimePathCheck:SKIP(local-kotlin)"
            uiState = uiState.copy(logs = appendLog("Debug: $runtimeCheckResult"))
            return
        }
        val base = buildInitialSession(
            config = state.ctx.config,
            poems = state.ctx.poems
        )
        val started = sessionBridge.reduce(base, SessionEvent.UserUiStart(System.currentTimeMillis()))
        val hasSpeak = started.actions.any { it is SessionAction.Speak }
        val hasStartListening = started.actions.any { it is SessionAction.StartListening }
        val trace = (sessionBridge as? SharedCoreBridge)?.getLastTrace()
        val runtimePathOk = trace?.path == "runtime"
        val hookOk = trace?.reason == "delegate-hook"
        val stateOk = started.state.type == SessionStateType.WAIT_POEM_NAME
        val pass = runtimePathOk && hookOk && stateOk && hasSpeak && hasStartListening
        val mode = if (trace != null) "${trace.path}/${trace.reason}" else "unknown"
        runtimeCheckResult = "RuntimePathCheck:${if (pass) "PASS" else "FAIL"}(${mode},${started.state.type})"
        uiState = uiState.copy(logs = appendLog("Debug: $runtimeCheckResult"))
    }

    fun runBridgeCodecCheck() {
        val base = buildInitialSession(
            config = state.ctx.config,
            poems = state.ctx.poems
        )
        val stateJson = sharedCoreCodec.encodeState(base)
        val event = SessionEvent.UserUiStart(System.currentTimeMillis())
        val eventJson = sharedCoreCodec.encodeEvent(event)
        val outputJson = localBridgeRuntime.reduce(stateJson, eventJson)
        if (outputJson == null) {
            codecCheckResult = "BridgeCodecCheck:FAIL(runtime-null)"
            uiState = uiState.copy(logs = appendLog("Debug: $codecCheckResult"))
            return
        }
        val decodeResult = sharedCoreCodec.decodeOutputWithReason(outputJson)
        val output = decodeResult.output
        if (output == null) {
            val reason = decodeResult.reason ?: "decode-null"
            codecCheckResult = "BridgeCodecCheck:FAIL(output-$reason)"
            uiState = uiState.copy(logs = appendLog("Debug: $codecCheckResult"))
            return
        }
        val hasSpeak = output.actions.any { it is SessionAction.Speak }
        val hasStartListening = output.actions.any { it is SessionAction.StartListening }
        val stateOk = output.state.type == SessionStateType.WAIT_POEM_NAME
        val pass = hasSpeak && hasStartListening && stateOk
        codecCheckResult = "BridgeCodecCheck:${if (pass) "PASS" else "FAIL"}(${output.state.type})"
        uiState = uiState.copy(logs = appendLog("Debug: $codecCheckResult"))
    }

    fun runBridgeEventRoundTripCheck() {
        val now = System.currentTimeMillis()

        val startEvent = SessionEvent.UserUiStart(now)
        val startRaw = sharedCoreCodec.encodeEvent(startEvent)
        val startDecoded = sharedCoreCodec.decodeEvent(startRaw)
        val startOk = startDecoded is SessionEvent.UserUiStart && startDecoded.now == now

        val asrEvent = SessionEvent.UserAsr("jing ye si", isFinal = true, confidence = 0.91f, now = now + 1)
        val asrRaw = sharedCoreCodec.encodeEvent(asrEvent)
        val asrDecoded = sharedCoreCodec.decodeEvent(asrRaw)
        val decodedConfidence = (asrDecoded as? SessionEvent.UserAsr)?.confidence
        val asrOk = asrDecoded is SessionEvent.UserAsr &&
            asrDecoded.text == "jing ye si" &&
            asrDecoded.isFinal &&
            asrDecoded.now == now + 1 &&
            decodedConfidence != null &&
            abs(decodedConfidence - 0.91f) < 0.0001f

        val pass = startOk && asrOk
        eventCheckResult =
            "BridgeEventCheck:${if (pass) "PASS" else "FAIL"}(start=${if (startOk) "ok" else "bad"},asr=${if (asrOk) "ok" else "bad"})"
        uiState = uiState.copy(logs = appendLog("Debug: $eventCheckResult"))
    }

    fun runAllBridgeChecks() {
        runBridgeEventRoundTripCheck()
        runBridgeCodecCheck()
        runRuntimePathCheck()
        runAsrErrorFlowCheck()

        val eventOk = eventCheckResult.startsWith("BridgeEventCheck:PASS")
        val codecOk = codecCheckResult.startsWith("BridgeCodecCheck:PASS")
        val runtimeOk =
            runtimeCheckResult.startsWith("RuntimePathCheck:PASS") ||
                runtimeCheckResult.startsWith("RuntimePathCheck:SKIP(local-kotlin)")
        val asrOk = debugCheckResult.startsWith("AsrErrorCheck:PASS")
        val pass = eventOk && codecOk && runtimeOk && asrOk
        allBridgeCheckResult =
            "AllBridgeChecks:${if (pass) "PASS" else "FAIL"}(event=${if (eventOk) "ok" else "bad"},codec=${if (codecOk) "ok" else "bad"},runtime=${if (runtimeOk) "ok" else "bad"},asr=${if (asrOk) "ok" else "bad"})"
        uiState = uiState.copy(logs = appendLog("Debug: $allBridgeCheckResult"))
    }

    private fun text(resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun resolveStatusText(type: SessionStateType, awaitingSpeech: Boolean): String {
        if (awaitingSpeech) {
            return text(R.string.waiting_input)
        }
        return when (type) {
            SessionStateType.IDLE -> text(R.string.status_tap_start)
            SessionStateType.WAIT_POEM_NAME -> text(R.string.status_wait_poem_name)
            SessionStateType.CONFIRM_POEM_CANDIDATE -> text(R.string.status_confirm_poem)
            SessionStateType.WAIT_DYNASTY_AUTHOR -> text(R.string.status_wait_author)
            SessionStateType.RECITE_READY -> text(R.string.status_recite_ready)
            SessionStateType.RECITING -> text(R.string.status_reciting)
            SessionStateType.HINT_OFFER -> text(R.string.status_hint_offer)
            SessionStateType.HINT_GIVEN -> text(R.string.status_hint_given)
            SessionStateType.FINISHED -> text(R.string.status_finished)
            SessionStateType.EXIT -> text(R.string.status_exit)
            else -> type.name
        }
    }

    private fun isTransientAsrError(code: Int): Boolean {
        return code == SpeechRecognizer.ERROR_AUDIO ||
            code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    }

    private fun scheduleTransientAsrRetry(delayMs: Int = settings.transientAsrRetryDelayMs) {
        transientRetryJob?.cancel()
        val baseDelayMs = delayMs.coerceIn(
            MIN_TRANSIENT_ASR_RETRY_DELAY_MS,
            MAX_TRANSIENT_ASR_RETRY_DELAY_MS
        )
        val jitterBound = (baseDelayMs / 4).coerceIn(40, 250)
        val jitter = Random.nextInt(from = -jitterBound, until = jitterBound + 1)
        val actualDelayMs = (baseDelayMs + jitter).coerceIn(
            MIN_TRANSIENT_ASR_RETRY_DELAY_MS,
            MAX_TRANSIENT_ASR_RETRY_DELAY_MS
        )
        transientRetryJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(actualDelayMs.toLong())
            if (!uiState.sessionActive || uiState.sessionPaused || isManuallyPaused) {
                return@launch
            }
            if (isSpeaking) {
                pendingStartListening = true
            } else {
                beginListening()
            }
        }
    }

    private fun appendLog(line: String): List<String> {
        return uiState.logs + line
    }

    private fun applyAsrTuningToSpeech(source: SettingsState = settings) {
        speech.setStopToStartCooldownMs(source.asrStopToStartCooldownMs)
    }

    private fun isAsrLogLine(line: String): Boolean {
        return line.contains("ASR")
    }

    override fun onCleared() {
        transientRetryJob?.cancel()
        transientRetryJob = null
        sharedCoreHookToken?.let { token ->
            SharedCoreRuntimeHooks.clearReduceHook(token)
            sharedCoreHookToken = null
        }
        speech.stopListening(reason = "viewmodel-cleared")
        speech.stopSpeak()
        speech.release()
        super.onCleared()
    }

    private data class VariantProviderResponse(
        val provider: String? = null,
        val lines: List<VariantProviderLine>? = null
    )

    private data class VariantProviderLine(
        val lineIndex: Int? = null,
        val variants: List<VariantCandidatePayload>? = null
    )

    private data class VariantCandidatePayload(
        val text: String? = null,
        val confidence: Double? = null
    )
}
