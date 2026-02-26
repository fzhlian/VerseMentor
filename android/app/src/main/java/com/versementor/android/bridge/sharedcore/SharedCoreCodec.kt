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

    fun decodeOutput(raw: String): SessionOutput? {
        val envelope = try {
            gson.fromJson(raw, SharedCoreReducerResponseEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return null

        val state = stateFromEnvelope(envelope.state) ?: return null
        val actions = envelope.actions.mapNotNull { actionFromEnvelope(it) }
        if (actions.size != envelope.actions.size) return null
        return SessionOutput(state, actions)
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
                noPoemIntentSince = ctx.noPoemIntentSince
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
            noPoemIntentSince = envelope.ctx.noPoemIntentSince
        )
        return SessionState(type, ctx)
    }

    private fun eventToEnvelope(event: SessionEvent): SharedCoreEventEnvelope {
        return when (event) {
            is SessionEvent.UserAsr -> SharedCoreEventEnvelope(
                type = "USER_ASR",
                text = event.text,
                isFinal = event.isFinal,
                confidence = event.confidence
            )
            is SessionEvent.Tick -> SharedCoreEventEnvelope(type = "TICK", now = event.now)
            SessionEvent.UserUiStart -> SharedCoreEventEnvelope(type = "USER_UI_START")
            SessionEvent.UserUiStop -> SharedCoreEventEnvelope(type = "USER_UI_STOP")
            is SessionEvent.VariantsFetched -> SharedCoreEventEnvelope(
                type = "EV_VARIANTS_FETCH_DONE",
                entry = event.entry
            )
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
    val noPoemIntentSince: Long? = null
)

data class SharedCoreEventEnvelope(
    val type: String,
    val text: String? = null,
    val isFinal: Boolean? = null,
    val confidence: Float? = null,
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
