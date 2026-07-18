package com.gagmate.app.data.api

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import com.gagmate.app.data.system.DebugLogState
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

    private val fileLoggingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val startTime = System.nanoTime()

        val requestBody = if (request.body != null) {
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            buffer.readUtf8()
        } else null

        try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val responseBody = response.body?.string() ?: ""

            // Always log to the general network log
            NetworkLogger.log(
                method = request.method,
                url = request.url.toString(),
                requestBody = requestBody,
                statusCode = response.code,
                responseBody = responseBody,
                durationMs = durationMs
            )

            // Also capture raw JSON for endpoints whose format is being investigated
            val path = request.url.encodedPath
            if (path.startsWith("/api/system/") || path.startsWith("/api/profiles/") || path.startsWith("/api/shots/")) {
                ApiDebugLogger.logResponse(path, response.code, responseBody)
            }
            DebugLogState.add("HTTP \${request.method}", "\$path \${response.code} \${responseBody.take(120)}")

            response.newBuilder()
                .body(responseBody.toResponseBody(response.body?.contentType()))
                .build()
        } catch (e: Exception) {
            NetworkLogger.logError(
                method = request.method,
                url = request.url.toString(),
                requestBody = requestBody,
                error = e.message ?: e.toString()
            )
            throw e
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor(fileLoggingInterceptor)
        .build()

    private var currentBaseUrl: String = "http://192.168.0.186/"

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
