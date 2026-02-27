package com.versementor.android.bridge.sharedcore

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.versementor.android.session.AppConfig
import com.versementor.android.session.Poem
import com.versementor.android.session.PoemIndex
import com.versementor.android.session.SessionAction
import com.versementor.android.session.SessionContext
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionState
import com.versementor.android.session.SessionStateType
import com.versementor.android.storage.PoemVariantsCacheEntry

class SharedCoreCodec(
    private val gson: Gson = Gson()
) {
    fun encodeState(state: SessionState): String {
        return gson.toJson(stateToEnvelope(state))
    }

    fun encodeEvent(event: SessionEvent): String {
        return gson.toJson(eventToEnvelope(event))
    }

    fun decodeState(raw: String): SessionState? {
        val envelope = try {
            gson.fromJson(raw, SharedCoreStateEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return null
        return try {
            stateFromEnvelope(envelope)
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeEvent(raw: String): SessionEvent? {
        val envelope = try {
            gson.fromJson(raw, SharedCoreEventEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return null
        return try {
            eventFromEnvelope(envelope)
        } catch (_: Throwable) {
            null
        }
    }

    fun encodeOutput(output: SessionOutput): String {
        val envelope = SharedCoreReducerResponseEnvelope(
            state = stateToEnvelope(output.state),
            actions = output.actions.map { actionToEnvelope(it) }
        )
        return gson.toJson(envelope)
    }

    fun decodeOutput(raw: String): SessionOutput? {
        return decodeOutputWithReason(raw).output
    }

    fun decodeOutputWithReason(raw: String): SharedCoreDecodeOutputResult {
        val envelope = try {
            gson.fromJson(raw, SharedCoreReducerResponseEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return SharedCoreDecodeOutputResult(output = null, reason = "invalid-json")

        val state = try {
            stateFromEnvelope(envelope.state)
        } catch (_: Throwable) {
            null
        }
            ?: return SharedCoreDecodeOutputResult(output = null, reason = "state-invalid")
        val rawActions = try {
            envelope.actions
        } catch (_: Throwable) {
            null
        } ?: return SharedCoreDecodeOutputResult(output = null, reason = "actions-invalid")

        val actionResults = try {
            rawActions.map {
                try {
                    actionFromEnvelope(it)
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (_: Throwable) {
            return SharedCoreDecodeOutputResult(output = null, reason = "actions-invalid")
        }
        if (actionResults.any { it == null }) {
            return SharedCoreDecodeOutputResult(output = null, reason = "actions-invalid")
        }
        val actions = actionResults.filterNotNull()
        return SharedCoreDecodeOutputResult(output = SessionOutput(state, actions), reason = null)
    }

    private fun stateToEnvelope(state: SessionState): SharedCoreStateEnvelope {
        val ctx = state.ctx
        return SharedCoreStateEnvelope(
            type = state.type.name,
            ctx = SharedCoreContextEnvelope(
                config = ctx.config,
                poems = ctx.poems,
                selectedPoem = ctx.selectedPoem,
                variantsCacheEntry = ctx.variantsCacheEntry,
                currentLineIdx = ctx.currentLineIdx,
                lastUserActiveAt = ctx.lastUserActiveAt,
                noPoemIntentSince = ctx.noPoemIntentSince,
                hintOfferSince = ctx.hintOfferSince
            )
        )
    }

    private fun stateFromEnvelope(envelope: SharedCoreStateEnvelope): SessionState? {
        val type = try {
            SessionStateType.valueOf(envelope.type)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val poems = envelope.ctx.poems
        val ctx = SessionContext(
            config = envelope.ctx.config,
            poemIndex = PoemIndex(poems),
            poems = poems,
            selectedPoem = envelope.ctx.selectedPoem,
            variantsCacheEntry = envelope.ctx.variantsCacheEntry,
            currentLineIdx = envelope.ctx.currentLineIdx,
            lastUserActiveAt = envelope.ctx.lastUserActiveAt,
            noPoemIntentSince = envelope.ctx.noPoemIntentSince,
            hintOfferSince = envelope.ctx.hintOfferSince
        )
        return SessionState(type, ctx)
    }

    private fun eventToEnvelope(event: SessionEvent): SharedCoreEventEnvelope {
        return when (event) {
            is SessionEvent.UserAsr -> SharedCoreEventEnvelope(
                type = "USER_ASR",
                text = event.text,
                isFinal = event.isFinal,
                confidence = event.confidence,
                now = event.now
            )
            is SessionEvent.UserAsrError -> SharedCoreEventEnvelope(
                type = "USER_ASR_ERROR",
                code = event.code,
                message = event.message
            )
            is SessionEvent.Tick -> SharedCoreEventEnvelope(type = "TICK", now = event.now)
            is SessionEvent.UserUiStart -> SharedCoreEventEnvelope(type = "USER_UI_START", now = event.now)
            SessionEvent.UserUiStop -> SharedCoreEventEnvelope(type = "USER_UI_STOP")
            is SessionEvent.VariantsFetched -> SharedCoreEventEnvelope(
                type = "EV_VARIANTS_FETCH_DONE",
                entry = event.entry
            )
        }
    }

    private fun eventFromEnvelope(envelope: SharedCoreEventEnvelope): SessionEvent? {
        return when (envelope.type) {
            "USER_ASR" -> {
                val text = envelope.text ?: return null
                val isFinal = envelope.isFinal ?: return null
                SessionEvent.UserAsr(
                    text = text,
                    isFinal = isFinal,
                    confidence = envelope.confidence,
                    now = envelope.now
                )
            }
            "USER_ASR_ERROR" -> {
                val code = envelope.code ?: return null
                val message = envelope.message ?: return null
                SessionEvent.UserAsrError(code = code, message = message)
            }
            "TICK" -> {
                val now = envelope.now ?: return null
                SessionEvent.Tick(now = now)
            }
            "USER_UI_START" -> SessionEvent.UserUiStart(now = envelope.now)
            "USER_UI_STOP" -> SessionEvent.UserUiStop
            "EV_VARIANTS_FETCH_DONE" -> SessionEvent.VariantsFetched(entry = envelope.entry)
            else -> null
        }
    }

    private fun actionToEnvelope(action: SessionAction): SharedCoreActionEnvelope {
        return when (action) {
            is SessionAction.Speak -> SharedCoreActionEnvelope(type = "SPEAK", text = action.text)
            SessionAction.StartListening -> SharedCoreActionEnvelope(type = "START_LISTENING")
            SessionAction.StopListening -> SharedCoreActionEnvelope(type = "STOP_LISTENING")
            is SessionAction.UpdateScreenHint -> SharedCoreActionEnvelope(type = "UPDATE_SCREEN_HINT", key = action.key)
            is SessionAction.FetchVariants -> SharedCoreActionEnvelope(type = "FETCH_VARIANTS", poem = action.poem)
        }
    }

    private fun actionFromEnvelope(envelope: SharedCoreActionEnvelope): SessionAction? {
        return when (envelope.type) {
            "SPEAK" -> envelope.text?.let { SessionAction.Speak(it) }
            "START_LISTENING" -> SessionAction.StartListening
            "STOP_LISTENING" -> SessionAction.StopListening
            "UPDATE_SCREEN_HINT" -> envelope.key?.let { SessionAction.UpdateScreenHint(it) }
            "FETCH_VARIANTS" -> envelope.poem?.let { SessionAction.FetchVariants(it) }
            else -> null
        }
    }
}

data class SharedCoreStateEnvelope(
    val type: String,
    val ctx: SharedCoreContextEnvelope
)

data class SharedCoreContextEnvelope(
    val config: AppConfig,
    val poems: List<Poem> = emptyList(),
    val selectedPoem: Poem? = null,
    val variantsCacheEntry: PoemVariantsCacheEntry? = null,
    val currentLineIdx: Int = 0,
    val lastUserActiveAt: Long? = null,
    val noPoemIntentSince: Long? = null,
    val hintOfferSince: Long? = null
)

data class SharedCoreEventEnvelope(
    val type: String,
    val text: String? = null,
    val isFinal: Boolean? = null,
    val confidence: Float? = null,
    val code: Int? = null,
    val message: String? = null,
    val now: Long? = null,
    val entry: PoemVariantsCacheEntry? = null
)

data class SharedCoreActionEnvelope(
    val type: String,
    val text: String? = null,
    val key: String? = null,
    val poem: Poem? = null
)

data class SharedCoreReducerResponseEnvelope(
    val state: SharedCoreStateEnvelope,
    val actions: List<SharedCoreActionEnvelope> = emptyList()
)

data class SharedCoreDecodeOutputResult(
    val output: SessionOutput?,
    val reason: String?
)
