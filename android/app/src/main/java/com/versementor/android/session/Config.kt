package com.versementor.android.session

import com.versementor.android.AccentToleranceState
import com.versementor.android.SettingsState
import com.versementor.android.storage.PreferenceStore

// Lightweight config mirroring shared-core defaults.
data class ReciteThresholds(
    val passScore: Double = 0.86,
    val partialScore: Double = 0.6,
    val minCoverage: Double = 0.6
)

data class Timeouts(
    val noPoemIntentExitSec: Int = 20,
    val reciteSilenceAskHintSec: Int = 6,
    val hintOfferWaitSec: Int = 8
)

data class VariantFetchConfig(
    val enableOnline: Boolean = true,
    val ttlDays: Int = 7
)

data class AppConfig(
    val toneRemind: Boolean = true,
    val accentTolerance: AccentToleranceState = AccentToleranceState(),
    val variants: VariantFetchConfig = VariantFetchConfig(),
    val timeouts: Timeouts = Timeouts(),
    val recite: ReciteThresholds = ReciteThresholds()
)

fun buildDefaultConfig(prefs: PreferenceStore): AppConfig {
    val settings = prefs.loadSettings()
    return AppConfig(
        toneRemind = settings.toneRemind,
        accentTolerance = settings.accentTolerance,
        variants = VariantFetchConfig(settings.variantsEnable, settings.variantTtlDays),
        timeouts = Timeouts(),
        recite = ReciteThresholds()
    )
}
