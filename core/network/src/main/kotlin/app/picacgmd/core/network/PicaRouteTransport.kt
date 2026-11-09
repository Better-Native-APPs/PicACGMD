package app.picacgmd.core.network

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The official init response also contains advertising fields; only addresses are consumed. */
object PicaInitAddresses {
    fun parse(body: String): List<String> {
        val addresses = try {
            val root = Json.parseToJsonElement(body) as? JsonObject
            (root?.get("addresses") as? JsonArray).orEmpty().mapNotNull { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(IpLiteral::normalizeOrNull)
            }.distinct()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        require(addresses.isNotEmpty()) { "未获取到分流地址" }
        return addresses
    }
}

const val ROUTE_PROBE_TIMEOUT_MILLIS = 5_000L

class PicaRouteTransport(private val initUrl: String = "http://68.183.234.72/init") {
    /** Main-safe: requests and socket cleanup run on [Dispatchers.IO]. */
    suspend fun fetchApiAddresses(proxyUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val client = PicaClientFactory.apiClient(PicaNetworkConfig(proxyUrl = proxyUrl)).newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val body = client.newCall(Request.Builder().url(initUrl).build()).consumeResponse { response ->
                if (!response.isSuccessful) throw IOException("获取分流地址失败：HTTP ${response.code}")
                response.body.string()
            }
            PicaInitAddresses.parse(body)
        } finally {
            client.release()
        }
    }

    /** Headers are enough: every HTTP status counts, redirects and response bodies are not followed. */
    suspend fun measureImageHost(host: String): Long = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val client = PicaClientFactory.imageClient().newBuilder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .connectTimeout(ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(ROUTE_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        try {
            client.newCall(Request.Builder().url(host).build()).consumeResponse {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            }
        } finally {
            client.release()
        }
    }
}

private suspend fun <T> Call.consumeResponse(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                consume(response)
            } catch (exception: Exception) {
                response.close()
                continuation.resumeWithException(exception)
                return
            }
            // Deliver the first-response result before closing/discarding a potentially slow body.
            try {
                continuation.resume(result)
            } finally {
                response.close()
            }
        }
    })
}

/** Closing pooled TLS connections writes to the socket; cleanup never turns a finished request into a failure. */
private fun OkHttpClient.release() {
    runCatching {
        dispatcher.cancelAll()
        connectionPool.evictAll()
        dispatcher.executorService.shutdown()
    }
}
