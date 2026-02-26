import type { KVStore, VariantCacheStore } from './storage'

export class PreferenceKVStore implements KVStore {
  private data = new Map<string, string>()

  async get(key: string): Promise<string | null> {
    return this.data.get(key) ?? null
  }

  async set(key: string, val: string): Promise<void> {
    this.data.set(key, val)
  }

  async del(key: string): Promise<void> {
    this.data.delete(key)
  }
}

export class PreferenceVariantCacheStore implements VariantCacheStore {
  private data = new Map<string, string>()

  async get(key: string): Promise<string | null> {
    return this.data.get(key) ?? null
  }

  async set(key: string, entry: string): Promise<void> {
    this.data.set(key, entry)
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key)
  }
}
