import type { ISpeechProvider } from './provider'

export type SpeechProviderId = 'iflytek' | 'volc'

type SpeechProviderFactory = () => ISpeechProvider

const VALID_PROVIDER_IDS: SpeechProviderId[] = ['iflytek', 'volc']

function isSpeechProviderId(value: string): value is SpeechProviderId {
  return VALID_PROVIDER_IDS.includes(value as SpeechProviderId)
}

export class SpeechProviderRegistry {
  private readonly factories = new Map<SpeechProviderId, SpeechProviderFactory>()

  register(id: SpeechProviderId, factory: SpeechProviderFactory): void {
    if (!isSpeechProviderId(id)) {
      throw new Error(`invalid-speech-provider-id:${id}`)
    }
    if (this.factories.has(id)) {
      throw new Error(`speech-provider-already-registered:${id}`)
    }
    this.factories.set(id, factory)
  }

  create(id: SpeechProviderId): ISpeechProvider {
    const factory = this.factories.get(id)
    if (!factory) {
      throw new Error(`speech-provider-not-registered:${id}`)
    }
    return factory()
  }

  list(): SpeechProviderId[] {
    return Array.from(this.factories.keys())
  }
}
