package com.lagradost.cloudstream3.network

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

/**
 * Fork fix: do not install the bundled Conscrypt provider on
 * Android < 7 (API 24). On the ZTE B860H (Android 6.0.1) the
 * BoringSSL inside Conscrypt aborts the whole process with SIGABRT
 * the first time its SecureRandom seeds itself (CRYPTO_once ->
 * RAND_bytes -> abort), as proven by 10/10 tombstones taken from
 * the device. A native abort cannot be caught by safe{} or by any
 * Java exception handler, and it killed the app right after the
 * splash logo on every launch. Older devices fall back to the
 * platform TLS provider, which still offers TLS 1.2 + SNI.
 */
private fun installConscryptProvider() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
    }
}

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}


// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    installConscryptProvider()
    
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_key), 0)
    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}