package com.versementor.android.net

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request

interface HttpClient {
    suspend fun <T> getJson(url: String, clazz: Class<T>): T
}

class OkHttpClientImpl(private val gson: Gson = Gson()) : HttpClient {
    private val client = OkHttpClient()

    override suspend fun <T> getJson(url: String, clazz: Class<T>): T {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Empty body")
            return gson.fromJson(body, clazz)
        }
    }
}
