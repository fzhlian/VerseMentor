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
    val reason: String
)

class FullDuplexAudioArbiter(
    policy: DuplexPolicy = DuplexPolicy()
) {
    private var listening = false
    private var speaking = false
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
    }

    fun onSpeechDetected(): BargeInDecision {
        if (!speaking) {
            return BargeInDecision(
                duckTts = false,
                stopTts = false,
                reason = "ignored-not-speaking"
            )
        }
        return when (policy.bargeInMode) {
            BargeInMode.NONE -> BargeInDecision(
                duckTts = false,
                stopTts = false,
                reason = "barge-in-disabled"
            )

            BargeInMode.DUCK_TTS -> BargeInDecision(
                duckTts = true,
                stopTts = false,
                reason = "barge-in-duck"
            )

            BargeInMode.STOP_TTS_ON_SPEECH -> BargeInDecision(
                duckTts = false,
                stopTts = true,
                reason = "barge-in-stop-tts"
            )
        }
    }
}