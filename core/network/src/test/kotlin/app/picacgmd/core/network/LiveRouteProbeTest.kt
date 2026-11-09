package app.picacgmd.core.network

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assume.assumeTrue

/** Opt-in diagnosis only: PICA_LIVE_PROBE=1, never part of offline CI acceptance. */
class LiveRouteProbeTest {
    @Test
    fun compareSignedApiResultsWithTransportReachability() = runBlocking {
        assumeTrue(System.getenv("PICA_LIVE_PROBE") == "1")
        val routes = listOf(ApiRoute.None) +
            listOf("172.67.7.24", "104.20.180.50", "172.67.208.169").map(ApiRoute::Address)
        routes.map { route ->
            async(Dispatchers.IO) {
                val client = PicaClientFactory.apiClient(PicaNetworkConfig(apiRoute = route)).newBuilder()
                    .proxy(Proxy.NO_PROXY)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                try {
                    val url = "${PicaProtocol.BASE_URL}keywords".toHttpUrl()
                    val request = Request.Builder().url(url).headers(PicaHeaderBuilder().build(url, "GET")).build()
                    val started = System.nanoTime()
                    try {
                        client.newCall(request).execute().use { response ->
                            val headersMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            val body = response.body.string()
                            val bodyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            println("LIVE_API route=$route headers=${headersMillis}ms body=${bodyMillis}ms http=${response.code} body=${body.take(200)}")
                        }
                        val apiStarted = System.nanoTime()
                        val result = PicaApi(client = client).hotKeywords()
                        println("LIVE_OLD_PROBE route=$route elapsed=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - apiStarted)}ms result=$result")
                    } catch (exception: Exception) {
                        println("LIVE_API route=$route elapsed=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}ms failure=$exception")
                    }
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
                try {
                    println("LIVE_FIXED_PROBE route=$route elapsed=${PicaRouteTransport().measureApiRoute(route)}ms")
                } catch (exception: Exception) {
                    println("LIVE_FIXED_PROBE route=$route failure=$exception")
                }
            }
        }.awaitAll()
        listOf("https://storage1.picacomic.com", "https://storage.wikawika.xyz", "https://s2.picacomic.com", "https://s3.picacomic.com")
            .map { host ->
                async(Dispatchers.IO) {
                    val started = System.nanoTime()
                    try {
                        println("LIVE_IMAGE host=$host elapsed=${PicaRouteTransport().measureImageHost(host)}ms")
                    } catch (exception: Exception) {
                        println("LIVE_IMAGE host=$host elapsed=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}ms failure=$exception")
                    }
                }
            }.awaitAll()
        Unit
    }
}
