package com.versementor.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.versementor.android.session.SessionAction
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionState
import com.versementor.android.session.SessionReducer
import com.versementor.android.session.SessionUiState
import com.versementor.android.session.SamplePoems
import com.versementor.android.session.buildDefaultConfig
import com.versementor.android.session.buildInitialSession
import com.versementor.android.speech.SpeechIO
import com.versementor.android.storage.PreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val MIN_VARIANT_TTL_DAYS = 1
        const val MAX_VARIANT_TTL_DAYS = 365
    }

    private val prefs = PreferenceStore(app)
    private val speech = SpeechIO(app)
    private val reducer = SessionReducer()

    var uiState: SessionUiState by androidx.compose.runtime.mutableStateOf(SessionUiState())
        private set

    var settings: SettingsState by androidx.compose.runtime.mutableStateOf(SettingsState())
        private set

    private var state: SessionState = buildInitialSession(
        config = buildDefaultConfig(prefs),
        poems = SamplePoems.poems
    )

    init {
        speech.onAsrResult = { text, isFinal, confidence ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                dispatch(SessionEvent.UserAsr(text, isFinal, confidence))
            }
        }
        speech.onSpeaking = { speaking ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                uiState = uiState.copy(statusText = if (speaking) "Speaking" else "Listening")
                if (!speaking) {
                    speech.startListening()
                }
            }
        }
        loadSettings()
        refreshVoices()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                dispatch(SessionEvent.Tick(System.currentTimeMillis()))
            }
        }
    }

    fun startSession() {
        dispatch(SessionEvent.UserUiStart)
    }

    fun dispatch(event: SessionEvent) {
        val output = reducer.reduce(state, event)
        state = output.state
        handleActions(output.actions)
        uiState = uiState.copy(statusText = state.type.name, lastHeard = if (event is SessionEvent.UserAsr) "ASR: ${event.text}" else uiState.lastHeard)
    }

    private fun handleActions(actions: List<SessionAction>) {
        actions.forEach { action ->
            when (action) {
                is SessionAction.Speak -> {
                    uiState = uiState.copy(lastSpoken = action.text, logs = uiState.logs + "TTS: ${action.text}")
                    speech.stopListening()
                    speech.speak(action.text, settings.ttsVoiceId)
                }
                SessionAction.StartListening -> {
                    speech.startListening()
                }
                SessionAction.StopListening -> {
                    speech.stopListening()
                }
                is SessionAction.UpdateScreenHint -> {
                    uiState = uiState.copy(logs = uiState.logs + "Hint: ${action.key}")
                }
                is SessionAction.FetchVariants -> {
                    uiState = uiState.copy(logs = uiState.logs + "Fetch variants for ${action.poem.title}")
                }
            }
        }
    }

    private fun loadSettings() {
        settings = prefs.loadSettings()
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
        val locales = if (follow) androidx.core.os.LocaleListCompat.getEmptyLocaleList() else androidx.core.os.LocaleListCompat.forLanguageTags("zh")
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setTtsVoice(id: String, name: String) {
        settings = settings.copy(ttsVoiceId = id, ttsVoiceName = name)
        prefs.saveSettings(settings)
        syncStateConfig()
    }

    fun setAccentTolerance(anAng: Boolean? = null, enEng: Boolean? = null, inIng: Boolean? = null, ianIang: Boolean? = null) {
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

    override fun onCleared() {
        speech.stopListening()
        speech.stopSpeak()
        speech.release()
        super.onCleared()
    }
}

