import type { BargeInMode, SpeechAudioProcessingOptions } from '../ports/speech'

export interface FullDuplexArbiterConfig {
  allowListeningDuringSpeaking: boolean
  bargeInMode: BargeInMode
  audioProcessing: SpeechAudioProcessingOptions
}

export interface ListeningDecision {
  allow: boolean
  reason: string
}

export interface SpeakingTransitionDecision {
  stopListening: boolean
  reason: string
}

export interface BargeInDecision {
  duckTts: boolean
  stopTts: boolean
  reason: string
}

export interface FullDuplexArbiterState {
  listening: boolean
  speaking: boolean
  config: FullDuplexArbiterConfig
}

const DEFAULT_CONFIG: FullDuplexArbiterConfig = {
  allowListeningDuringSpeaking: true,
  bargeInMode: 'stop_tts_on_speech',
  audioProcessing: {
    echoCancellation: true,
    noiseSuppression: true
  }
}

function sanitizeConfig(next?: Partial<FullDuplexArbiterConfig>): FullDuplexArbiterConfig {
  if (!next) {
    return {
      ...DEFAULT_CONFIG,
      audioProcessing: { ...DEFAULT_CONFIG.audioProcessing }
    }
  }
  return {
    allowListeningDuringSpeaking:
      next.allowListeningDuringSpeaking ?? DEFAULT_CONFIG.allowListeningDuringSpeaking,
    bargeInMode: next.bargeInMode ?? DEFAULT_CONFIG.bargeInMode,
    audioProcessing: {
      echoCancellation:
        next.audioProcessing?.echoCancellation ?? DEFAULT_CONFIG.audioProcessing.echoCancellation,
      noiseSuppression:
        next.audioProcessing?.noiseSuppression ?? DEFAULT_CONFIG.audioProcessing.noiseSuppression
    }
  }
}

export class FullDuplexAudioArbiter {
  private listening = false
  private speaking = false
  private config: FullDuplexArbiterConfig

  constructor(config?: Partial<FullDuplexArbiterConfig>) {
    this.config = sanitizeConfig(config)
  }

  updateConfig(config?: Partial<FullDuplexArbiterConfig>): FullDuplexArbiterConfig {
    this.config = sanitizeConfig(config)
    return this.getConfig()
  }

  getConfig(): FullDuplexArbiterConfig {
    return {
      allowListeningDuringSpeaking: this.config.allowListeningDuringSpeaking,
      bargeInMode: this.config.bargeInMode,
      audioProcessing: { ...this.config.audioProcessing }
    }
  }

  requestStartListening(): ListeningDecision {
    if (this.speaking && !this.config.allowListeningDuringSpeaking) {
      return {
        allow: false,
        reason: 'blocked-listening-during-speaking'
      }
    }
    this.listening = true
    return {
      allow: true,
      reason: this.speaking ? 'listening-allowed-during-speaking' : 'listening-started'
    }
  }

  stopListening(): void {
    this.listening = false
  }

  onSpeakingStarted(): SpeakingTransitionDecision {
    this.speaking = true
    if (this.listening && !this.config.allowListeningDuringSpeaking) {
      this.listening = false
      return {
        stopListening: true,
        reason: 'speaking-started-stop-listening'
      }
    }
    return {
      stopListening: false,
      reason: 'speaking-started'
    }
  }

  onSpeakingStopped(): void {
    this.speaking = false
  }

  onSpeechDetected(): BargeInDecision {
    if (!this.speaking) {
      return {
        duckTts: false,
        stopTts: false,
        reason: 'ignored-not-speaking'
      }
    }

    if (this.config.bargeInMode === 'duck_tts') {
      return {
        duckTts: true,
        stopTts: false,
        reason: 'barge-in-duck'
      }
    }

    if (this.config.bargeInMode === 'stop_tts_on_speech') {
      return {
        duckTts: false,
        stopTts: true,
        reason: 'barge-in-stop-tts'
      }
    }

    return {
      duckTts: false,
      stopTts: false,
      reason: 'barge-in-disabled'
    }
  }

  getState(): FullDuplexArbiterState {
    return {
      listening: this.listening,
      speaking: this.speaking,
      config: this.getConfig()
    }
  }
}