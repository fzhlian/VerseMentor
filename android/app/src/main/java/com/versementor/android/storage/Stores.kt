package com.versementor.android.storage

import com.google.gson.Gson

interface KVStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun del(key: String)
}

interface VariantCacheStore {
    suspend fun get(key: String): PoemVariantsCacheEntry?
    suspend fun set(key: String, entry: PoemVariantsCacheEntry)
    suspend fun delete(key: String)
}

data class PoemVariantsCacheEntry(
    val poemId: String,
    val variants: PoemVariants,
    val cachedAt: Long,
    val expiresAt: Long
)

data class PoemVariants(
    val poemId: String,
    val lines: List<PoemLineVariant>,
    val sourceTags: List<String>
)

data class PoemLineVariant(
    val lineIndex: Int,
    val variants: List<String>
)

class SharedPrefsKVStore(private val prefs: PreferenceStore) : KVStore {
    override suspend fun get(key: String): String? = prefs.getString(key)
    override suspend fun set(key: String, value: String) = prefs.putString(key, value)
    override suspend fun del(key: String) = prefs.remove(key)
}

class SharedPrefsVariantCacheStore(private val prefs: PreferenceStore) : VariantCacheStore {
    private val gson = Gson()

    override suspend fun get(key: String): PoemVariantsCacheEntry? {
        val raw = prefs.getString("variantCache:$key") ?: return null
        return gson.fromJson(raw, PoemVariantsCacheEntry::class.java)
    }

    override suspend fun set(key: String, entry: PoemVariantsCacheEntry) {
        prefs.putString("variantCache:$key", gson.toJson(entry))
    }

    override suspend fun delete(key: String) {
        prefs.remove("variantCache:$key")
    }
}
