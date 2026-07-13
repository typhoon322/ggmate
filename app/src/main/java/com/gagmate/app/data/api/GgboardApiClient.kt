package com.gagmate.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit client factory for connecting to Gagguino ggboard.
 * Handles dynamic base URL switching and connection configuration.
 */
object GgboardApiClient {

    private const val CONNECT_TIMEOUT = 5L
    private const val READ_TIMEOUT = 10L
    private const val WRITE_TIMEOUT = 10L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private var currentBaseUrl: String = "http://192.168.4.1/"

    private var retrofit: Retrofit = buildRetrofit(currentBaseUrl)

    private var api: GgboardApi = retrofit.create(GgboardApi::class.java)

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun getApi(): GgboardApi = api

    /**
     * Update the base URL and rebuild the Retrofit instance.
     * Returns true if the URL changed.
     */
    fun updateBaseUrl(newBaseUrl: String): Boolean {
        val normalized = if (newBaseUrl.endsWith("/")) newBaseUrl else "$newBaseUrl/"
        if (normalized == currentBaseUrl) return false
        currentBaseUrl = normalized
        retrofit = buildRetrofit(currentBaseUrl)
        api = retrofit.create(GgboardApi::class.java)
        return true
    }

    fun getCurrentBaseUrl(): String = currentBaseUrl
}
