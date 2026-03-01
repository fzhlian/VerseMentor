# Volcengine Provider Adapter

`VolcProvider` implements `ISpeechProvider` and requires runtime config injection.
No secrets are hardcoded in source.

## Required Config Fields

- `appId`
- `accessKey`
- `secretKey`

## Config Injection Sources

`VolcProvider` resolves credentials in this order:

1. `config.direct`
2. `config.kv` (`${kvPrefix}.appId`, `${kvPrefix}.accessKey`, `${kvPrefix}.secretKey`)
3. `config.env` keys (defaults below)

Defaults:

- `VOLC_APP_ID`
- `VOLC_ACCESS_KEY`
- `VOLC_SECRET_KEY`

Optional knobs:

- `kvPrefix` (default: `volc`)
- `envKeys` (override env key names)
- `voices` (custom static voice list)

## SDK Client Injection

Adapter does not import a vendor SDK directly.
Pass `clientFactory(credentials)` and return a `VolcClient` implementation that:

- creates ASR streaming sessions (`createAsrSession`)
- requests TTS results (`requestTts`) as URL or PCM stream
- optionally disposes SDK resources (`dispose`)

## TTS Playback Contract

`requestTts` can return:

- `type: "url"` with `url`
- `type: "pcm"` with `stream`

Provider emits `SpeakEvent.start` with either `url` or `pcmStream`.
`SpeechIOv3Impl` handles playback via `IAudioOutput.playUrl` / `playPcmStream`.
