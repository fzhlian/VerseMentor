export * from './interfaces'
export { ScriptPreference, Poem as ScriptPoem, PoemLineVariant, PoemVariants, TtsVoice } from './models'
export * from './fsm'
export * from './bridge/session_driver'
export * from './bridge/session_contract'
export * from './bridge/session_runner_v3'
export * from './poems'
export * from './variants'
export * from './utils/zh_normalize'
export * from './utils/similarity'
export * from './utils/id'
export * from './ports/storage'
export * from './ports/net'
export * from './ports/speech'
export type {
  AsrEvent as AsrEventV3,
  SpeakEvent as SpeakEventV3,
  ListenOptions as ListenOptionsV3,
  SpeakOptions as SpeakOptionsV3,
  VoiceInfo as VoiceInfoV3,
  SpeechStatus as SpeechStatusV3,
  ISpeechIOv3
} from './ports/speech_v3'
export * from './speech/full_duplex_arbiter'
export type { ISpeechProvider } from './speech/provider'
export type { IAudioCapture, IAudioOutput } from './speech/audio_ports'
export { SpeechProviderRegistry } from './speech/provider_registry'
export type { SpeechProviderId as RegistrySpeechProviderId } from './speech/provider_registry'
export { SpeechIOv3Impl } from './speech/speech_io_v3_impl'
export {
  IflytekProvider,
  type IflytekProviderOptions,
  type IflytekProviderConfig,
  type IflytekCredentials,
  type IflytekClient,
  type IflytekAsrSession,
  type IflytekAsrCallbacks,
  type IflytekAsrParams,
  type IflytekTtsResult
} from './providers/iflytek/IflytekProvider'
export {
  VolcProvider,
  type VolcProviderOptions,
  type VolcProviderConfig,
  type VolcCredentials,
  type VolcClient,
  type VolcAsrSession,
  type VolcAsrCallbacks,
  type VolcAsrParams,
  type VolcTtsResult
} from './providers/volc/VolcProvider'
export * from './config/defaults'
export * from './data/models'
export * from './data/sample_poems'
export * from './data/poem_index'
export * from './nlu/text_cleaner'
export * from './nlu/intent'
export * from './nlu/poem_title_matcher'
export * from './kb/dynasty_kb'
export * from './kb/author_kb'
export * from './variants/variant_provider'
export * from './variants/variant_cache'
export * from './recite/matcher'
export * from './fsm/session_fsm'
export * from './persona/praise'
