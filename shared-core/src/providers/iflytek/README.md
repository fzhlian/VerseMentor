# iFlytek Provider Adapter

`IflytekProvider` implements `ISpeechProvider` and requires runtime config injection.
No secrets are hardcoded in source.

## Required Config Fields

- `appId`
- `apiKey`
- `apiSecret`

## Config Injection Sources

`IflytekProvider` resolves credentials in this order:

1. `config.direct`
2. `config.kv` (`${kvPrefix}.appId`, `${kvPrefix}.apiKey`, `${kvPrefix}.apiSecret`)
3. `config.env` keys (defaults below)

Defaults:

- `IFLYTEK_APP_ID`
- `IFLYTEK_API_KEY`
- `IFLYTEK_API_SECRET`

Optional knobs:

- `kvPrefix` (default: `iflytek`)
- `envKeys` (override env key names)
- `voices` (custom static voice list)

## SDK Client Injection

Adapter does not import a vendor SDK directly.
Pass `clientFactory(credentials)` and return an `IflytekClient` implementation that:

- creates ASR streaming sessions (`createAsrSession`)
- requests TTS results (`requestTts`) as URL or PCM stream
- optionally disposes SDK resources (`dispose`)

## TTS Playback Contract

`requestTts` can return:

- `type: "url"` with `url`
- `type: "pcm"` with `stream`

Provider emits `SpeakEvent.start` with either `url` or `pcmStream`.
`SpeechIOv3Impl` handles playback via `IAudioOutput.playUrl` / `playPcmStream`.
