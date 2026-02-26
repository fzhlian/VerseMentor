package com.versementor.android

data class AccentToleranceState(
    val anAng: Boolean = true,
    val enEng: Boolean = true,
    val inIng: Boolean = true,
    val ianIang: Boolean = true
)

data class VoiceOption(
    val id: String,
    val displayName: String
)

data class SettingsState(
    val followSystem: Boolean = true,
    val ttsVoiceId: String = "",
    val ttsVoiceName: String = "",
    val accentTolerance: AccentToleranceState = AccentToleranceState(),
    val toneRemind: Boolean = true,
    val variantsEnable: Boolean = true,
    val variantTtlDays: Int = 7,
    val dynastyMappings: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val ttsVoices: List<VoiceOption> = emptyList()
)
