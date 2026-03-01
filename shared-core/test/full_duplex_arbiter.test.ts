import { describe, expect, test } from 'vitest'
import { FullDuplexAudioArbiter } from '../src/speech/full_duplex_arbiter'

describe('FullDuplexAudioArbiter', () => {
  test('defaults allow listening during speaking', () => {
    const arbiter = new FullDuplexAudioArbiter()

    arbiter.onSpeakingStarted()
    const decision = arbiter.requestStartListening()

    expect(decision).toEqual({
      allow: true,
      reason: 'listening-allowed-during-speaking'
    })
    expect(arbiter.getState().listening).toBe(true)
  })

  test('blocks listening during speaking when allowListeningDuringSpeaking is false', () => {
    const arbiter = new FullDuplexAudioArbiter({ allowListeningDuringSpeaking: false })

    arbiter.onSpeakingStarted()
    const decision = arbiter.requestStartListening()

    expect(decision).toEqual({
      allow: false,
      reason: 'blocked-listening-during-speaking'
    })
    expect(arbiter.getState().listening).toBe(false)
  })

  test('stops active listening when speaking begins and duplex is disabled', () => {
    const arbiter = new FullDuplexAudioArbiter({ allowListeningDuringSpeaking: false })

    const start = arbiter.requestStartListening()
    expect(start.allow).toBe(true)

    const transition = arbiter.onSpeakingStarted()

    expect(transition).toEqual({
      stopListening: true,
      reason: 'speaking-started-stop-listening'
    })
    expect(arbiter.getState().listening).toBe(false)
  })

  test('barge-in duck mode requests TTS ducking', () => {
    const arbiter = new FullDuplexAudioArbiter({ bargeInMode: 'duck_tts' })

    arbiter.onSpeakingStarted()
    const decision = arbiter.onSpeechDetected()

    expect(decision).toEqual({
      duckTts: true,
      stopTts: false,
      reason: 'barge-in-duck'
    })
  })

  test('barge-in stop mode requests stopping TTS', () => {
    const arbiter = new FullDuplexAudioArbiter({ bargeInMode: 'stop_tts_on_speech' })

    arbiter.onSpeakingStarted()
    const decision = arbiter.onSpeechDetected()

    expect(decision).toEqual({
      duckTts: false,
      stopTts: true,
      reason: 'barge-in-stop-tts'
    })
  })

  test('config update preserves explicit audio processing toggles', () => {
    const arbiter = new FullDuplexAudioArbiter()

    const next = arbiter.updateConfig({
      allowListeningDuringSpeaking: true,
      bargeInMode: 'none',
      audioProcessing: {
        echoCancellation: false,
        noiseSuppression: true
      }
    })

    expect(next).toEqual({
      allowListeningDuringSpeaking: true,
      bargeInMode: 'none',
      audioProcessing: {
        echoCancellation: false,
        noiseSuppression: true
      }
    })
  })
})