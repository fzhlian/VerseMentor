import type { KVStore, VariantCacheStore } from './storage'

export class PreferenceKVStore implements KVStore {
  private static data = new Map<string, string>()

  async get(key: string): Promise<string | null> {
    return PreferenceKVStore.data.get(key) ?? null
  }

  async set(key: string, val: string): Promise<void> {
    PreferenceKVStore.data.set(key, val)
  }

  async del(key: string): Promise<void> {
    PreferenceKVStore.data.delete(key)
  }
}

export class PreferenceVariantCacheStore implements VariantCacheStore {
  private static data = new Map<string, string>()

  async get(key: string): Promise<string | null> {
    return PreferenceVariantCacheStore.data.get(key) ?? null
  }

  async set(key: string, entry: string): Promise<void> {
    PreferenceVariantCacheStore.data.set(key, entry)
  }

  async delete(key: string): Promise<void> {
    PreferenceVariantCacheStore.data.delete(key)
  }
}
