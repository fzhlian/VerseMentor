# Speech Port V3

`shared-core/src/ports/speech_v3.ts` defines the full-duplex speech contract for platform adapters.

## Key Types

- `AsrEvent`
  - `ready`
  - `partial(text, confidence?)`
  - `final(text, confidence?)`
  - `vad(state)` where `state` is `speech_start | speech_end`
  - `volume(level)` where `level` is expected in `0..1`
  - `error(code, message)`
- `SpeakEvent`
  - `start`
  - `end`
  - `error(code, message)`

## ListenOptions (Full-Duplex)

- `lang`: fixed to `"zh-CN"`
- `enablePartial`: enable/disable partial ASR results
- `vad`: tunable VAD windows
  - `speechStartMs?`
  - `speechEndMs?`
- `fullDuplex`
  - `allowListeningDuringSpeaking`: allow ASR while TTS is active
  - `bargeInMode`
    - `none`: keep TTS unchanged
    - `duck_tts`: reduce TTS output volume during user speech
    - `stop_tts_on_speech`: stop TTS when user speech is detected
  - `duckVolume?`: target ducked TTS volume in range `0..1`
- `aec?`: acoustic echo cancellation toggle (platform-dependent)
- `ns?`: noise suppression toggle (platform-dependent)

## Runtime Interface

`ISpeechIOv3` is async and event-driven:

- `startListening(opts): Promise<void>`
- `stopListening(): Promise<void>`
- `onAsrEvent(cb): () => void` (unsubscribe function)
- `speak(text, opts): Promise<void>`
- `stopSpeak(): Promise<void>`
- `onSpeakEvent(cb): () => void` (unsubscribe function)
- `listVoices(): Promise<Array<{id,name}>>`
- `getStatus(): Promise<{ asrReady; ttsReady; activeProvider; lastError? }>`

## Legacy Port

`shared-core/src/ports/speech.ts` remains for compatibility.
`ISpeechIO` is marked deprecated and should not be used by new flow.
