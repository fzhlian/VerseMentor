import { PoemVariants } from '../models'

export interface PoemVariantsCacheEntry {
  poemId: string
  variants: PoemVariants
  cachedAt: number
  expiresAt: number
}

export interface KVStore {
  get(key: string): Promise<string | null>
  set(key: string, val: string): Promise<void>
  del(key: string): Promise<void>
}

export interface VariantCacheStore {
  get(key: string): Promise<PoemVariantsCacheEntry | null>
  set(key: string, entry: PoemVariantsCacheEntry): Promise<void>
  delete(key: string): Promise<void>
}

export class InMemoryKVStore implements KVStore {
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

export class InMemoryVariantCacheStore implements VariantCacheStore {
  private data = new Map<string, PoemVariantsCacheEntry>()

  async get(key: string): Promise<PoemVariantsCacheEntry | null> {
    return this.data.get(key) ?? null
  }

  async set(key: string, entry: PoemVariantsCacheEntry): Promise<void> {
    this.data.set(key, entry)
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key)
  }
}
