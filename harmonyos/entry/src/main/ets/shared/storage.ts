export interface KVStore {
  get(key: string): Promise<string | null>
  set(key: string, val: string): Promise<void>
  del(key: string): Promise<void>
}

export interface VariantCacheStore {
  get(key: string): Promise<string | null>
  set(key: string, entry: string): Promise<void>
  delete(key: string): Promise<void>
}
