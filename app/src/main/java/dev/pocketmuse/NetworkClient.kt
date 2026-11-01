package dev.pocketmuse

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared pool; retries belong to the caller so a request is never replayed silently. */
internal object NetworkClient {
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
