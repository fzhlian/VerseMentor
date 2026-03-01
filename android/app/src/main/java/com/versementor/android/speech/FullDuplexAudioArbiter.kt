package com.versementor.android.speech

data class ListeningDecision(
    val allow: Boolean,
    val reason: String
)

data class SpeakingTransitionDecision(
    val stopListening: Boolean,
    val reason: String
)

data class BargeInDecision(
    val duckTts: Boolean,
    val stopTts: Boolean,
    val duckVolume: Float? = null,
    val restoreVolume: Boolean = false,
    val reason: String
)

class FullDuplexAudioArbiter(
    policy: DuplexPolicy = DuplexPolicy()
) {
    private var listening = false
    private var speaking = false
    private var currentDuck = 1f
    private var policy: DuplexPolicy = policy

    fun updatePolicy(next: DuplexPolicy) {
        policy = next
    }

    fun snapshotPolicy(): DuplexPolicy {
        return policy
    }

    fun requestStartListening(): ListeningDecision {
        if (speaking && !policy.allowListeningDuringSpeaking) {
            return ListeningDecision(
                allow = false,
                reason = "blocked-listening-during-speaking"
            )
        }
        listening = true
        return ListeningDecision(
            allow = true,
            reason = if (speaking) "listening-allowed-during-speaking" else "listening-started"
        )
    }

    fun onListeningStopped() {
        listening = false
    }

    fun onSpeakingStarted(): SpeakingTransitionDecision {
        speaking = true
        if (listening && !policy.allowListeningDuringSpeaking) {
            listening = false
            return SpeakingTransitionDecision(
                stopListening = true,
                reason = "speaking-started-stop-listening"
            )
        }
        return SpeakingTransitionDecision(
            stopListening = false,
            reason = "speaking-started"
        )
    }

    fun onSpeakingStopped() {
        speaking = false
        currentDuck = 1f
    }

    fun onSpeechDetected(): BargeInDecision {
        if (!speaking) {
            return BargeInDecision(
                duckTts = false,
                stopTts = false,
                duckVolume = null,
                restoreVolume = false,
                reason = "ignored-not-speaking"
            )
        }
        return when (policy.bargeInMode) {
            BargeInMode.NONE -> BargeInDecision(
                duckTts = false,
                stopTts = false,
                duckVolume = null,
                restoreVolume = false,
                reason = "barge-in-disabled"
            )

            BargeInMode.DUCK_TTS -> BargeInDecision(
                duckTts = true,
                stopTts = false,
                duckVolume = policy.duckVolume.coerceIn(0f, 1f),
                restoreVolume = false,
                reason = "barge-in-duck"
            )

            BargeInMode.STOP_TTS_ON_SPEECH -> BargeInDecision(
                duckTts = false,
                stopTts = true,
                duckVolume = null,
                restoreVolume = false,
                reason = "barge-in-stop-tts"
            )
        }
    }

    fun onSpeechStartDetected(): BargeInDecision {
        return onSpeechDetected()
    }

    fun onSpeechEndDetected(): BargeInDecision {
        if (!speaking || policy.bargeInMode != BargeInMode.DUCK_TTS) {
            return BargeInDecision(
                duckTts = false,
                stopTts = false,
                duckVolume = null,
                restoreVolume = false,
                reason = "speech-end-ignored"
            )
        }
        if (currentDuck >= 0.99f) {
            return BargeInDecision(
                duckTts = false,
                stopTts = false,
                duckVolume = null,
                restoreVolume = false,
                reason = "duck-not-applied"
            )
        }
        currentDuck = 1f
        return BargeInDecision(
            duckTts = false,
            stopTts = false,
            duckVolume = 1f,
            restoreVolume = true,
            reason = "speech-end-restore-tts"
        )
    }

    fun onDuckApplied(volume: Float) {
        currentDuck = volume.coerceIn(0f, 1f)
    }
}
