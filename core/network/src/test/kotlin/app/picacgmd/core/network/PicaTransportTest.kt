package app.picacgmd.core.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request

class PicaTransportTest {
    @Test
    fun builtInDnsRoutesResolveWithoutSystemDnsAndPreserveHostIdentity() {
        val unexpectedDns = Dns { error("System DNS must not be used for a split API route") }
        listOf(
            ApiRoute.Split1 to "172.67.7.24",
            ApiRoute.Split2 to "104.20.180.50",
            ApiRoute.Split3 to "172.67.208.169",
        ).forEach { (route, expected) ->
            val resolved = PicaDns(route, unexpectedDns).lookup(PicaProtocol.API_HOST).single()
            assertEquals(expected, resolved.hostAddress)
            assertEquals(PicaProtocol.API_HOST, resolved.hostName)
        }
    }

    @Test
    fun noRouteUsesSystemDnsAndOtherHostsAreNeverRewritten() {
        val calls = mutableListOf<String>()
        val addresses = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val system = Dns { hostname -> calls.add(hostname); addresses }
        assertEquals(addresses, PicaDns(ApiRoute.None, system).lookup(PicaProtocol.API_HOST))
        assertEquals(addresses, PicaDns(ApiRoute.Split1, system).lookup("s2.picacomic.com"))
        assertEquals(listOf(PicaProtocol.API_HOST, "s2.picacomic.com"), calls)
    }

    @Test
    fun customDnsSupportsIpv4AndIpv6LiteralsOnly() {
        assertEquals("192.0.2.8", PicaDns(ApiRoute.Custom("192.0.2.8")).lookup(PicaProtocol.API_HOST).single().hostAddress)
        assertEquals(
            InetAddress.getByName("2001:db8::8"),
            PicaDns(ApiRoute.Custom("2001:db8::8")).lookup(PicaProtocol.API_HOST).single(),
        )
        assertFailsWith<IllegalArgumentException> { PicaDns(ApiRoute.Custom("example.com")) }
        assertFailsWith<IllegalArgumentException> { PicaDns(ApiRoute.Custom("256.2.3.4")) }
    }

    @Test
    fun clientFactoryAppliesTimeoutsAndDoesNotRetryMutations() {
        val client = PicaClientFactory.apiClient()
        assertEquals(60_000, client.callTimeoutMillis)
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertEquals(10_000, client.writeTimeoutMillis)
        assertEquals(16, client.dispatcher.maxRequestsPerHost)
        assertEquals(false, client.retryOnConnectionFailure)
        assertEquals(false, client.followRedirects)
    }

    @Test
    fun parsesExplicitHttpAndSocksProxies() {
        val http = PicaClientFactory.parseProxy("http://127.0.0.1:8080")
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(8080, (http.address() as InetSocketAddress).port)
        val socks = PicaClientFactory.parseProxy("socks5://localhost:1080")
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertEquals(1080, (socks.address() as InetSocketAddress).port)
        assertFailsWith<IllegalArgumentException> { PicaClientFactory.parseProxy("https://localhost:8080") }
        assertFailsWith<IllegalArgumentException> { PicaClientFactory.parseProxy("http://localhost") }
        assertFailsWith<IllegalArgumentException> { PicaClientFactory.parseProxy("http://localhost:8080/path") }
    }

    @Test
    fun imageClientDoesNotSendApiHeaders() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("image bytes").build())
            val client = PicaClientFactory.imageClient()
            client.newCall(Request.Builder().url(server.url("/static/test.jpg")).build()).execute().use {
                assertEquals("image bytes", it.body.string())
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            listOf("api-key", "authorization", "signature").forEach { assertNull(request.headers[it]) }
        }
    }
}
