import { describe, expect, test } from 'vitest'
import type { ISpeechProvider } from '../src/speech/provider'
import { SpeechProviderRegistry } from '../src/speech/provider_registry'

function buildMockProvider(name: string): ISpeechProvider {
  return {
    name,
    async init(): Promise<void> {},
    async dispose(): Promise<void> {},
    async asrStart(): Promise<void> {},
    async asrPushPcm(): Promise<void> {},
    async asrStop(): Promise<void> {},
    onAsr(): () => void {
      return () => {}
    },
    async ttsStart(): Promise<void> {},
    async ttsStop(): Promise<void> {},
    onTts(): () => void {
      return () => {}
    },
    async listVoices(): Promise<Array<{ id: string; name: string }>> {
      return []
    }
  }
}

describe('SpeechProviderRegistry', () => {
  test('register + list keeps registration order', () => {
    const registry = new SpeechProviderRegistry()
    registry.register('iflytek', () => buildMockProvider('iflytek'))
    registry.register('volc', () => buildMockProvider('volc'))

    expect(registry.list()).toEqual(['iflytek', 'volc'])
  })

  test('create returns provider instance from factory', () => {
    const registry = new SpeechProviderRegistry()
    registry.register('iflytek', () => buildMockProvider('iflytek'))

    const provider = registry.create('iflytek')

    expect(provider.name).toBe('iflytek')
  })

  test('create throws when provider is missing', () => {
    const registry = new SpeechProviderRegistry()

    expect(() => registry.create('volc')).toThrow('speech-provider-not-registered:volc')
  })

  test('register throws on duplicate provider id', () => {
    const registry = new SpeechProviderRegistry()
    registry.register('iflytek', () => buildMockProvider('iflytek'))

    expect(() => registry.register('iflytek', () => buildMockProvider('iflytek-2'))).toThrow(
      'speech-provider-already-registered:iflytek'
    )
  })
})
