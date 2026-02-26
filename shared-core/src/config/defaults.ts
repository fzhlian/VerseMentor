export type TonePolicy = 'ignore' | 'remind'

export interface AccentTolerance {
  an_ang: boolean
  en_eng: boolean
  in_ing: boolean
  ian_iang: boolean
}

export interface VariantFetchConfig {
  enableOnline: boolean
  ttlDays: number
  providerWeights: Record<string, number>
  maxVariantsPerLine: number
}

export interface Timeouts {
  noPoemIntentExitSec: number
  reciteSilenceAskHintSec: number
  hintOfferWaitSec: number
}

export interface ReciteThresholds {
  passScore: number
  partialScore: number
  minCoverage: number
}

export interface AppConfig {
  tonePolicy: TonePolicy
  toneRemind: boolean
  accentTolerance: AccentTolerance
  variants: VariantFetchConfig
  timeouts: Timeouts
  recite: ReciteThresholds
  ui: {
    followSystem: boolean
    uiLang: string
  }
}

export const DEFAULTS: AppConfig = {
  tonePolicy: 'remind',
  toneRemind: true,
  accentTolerance: {
    an_ang: true,
    en_eng: true,
    in_ing: true,
    ian_iang: true
  },
  variants: {
    enableOnline: true,
    ttlDays: 7,
    providerWeights: {
      default: 1
    },
    maxVariantsPerLine: 4
  },
  timeouts: {
    noPoemIntentExitSec: 20,
    reciteSilenceAskHintSec: 6,
    hintOfferWaitSec: 8
  },
  recite: {
    passScore: 0.86,
    partialScore: 0.6,
    minCoverage: 0.6
  },
  ui: {
    followSystem: true,
    uiLang: 'system'
  }
}
