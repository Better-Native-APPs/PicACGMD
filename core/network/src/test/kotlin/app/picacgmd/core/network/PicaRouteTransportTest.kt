package app.picacgmd.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class PicaRouteTransportTest {
    @Test
    fun initIgnoresUnknownAdvertisingFieldsAndDropsInvalidAddresses() {
        val result = PicaInitAddresses.parse("""{
            "status":"ok", "waka":"ignored", "adKeyword":"ignored", "unknown":{"x":1},
            "addresses":["104.19.53.76","example.com","256.1.1.1","1:2:3",null,42,"2001:db8::1","104.19.53.76"]
        }""")
        assertEquals(listOf("104.19.53.76", IpLiteral.normalize("2001:db8::1")), result)
    }

    @Test
    fun initRejectsEmptyMissingInvalidAndMalformedListsWithChineseMessage() {
        listOf("{}", "{\"addresses\":[]}", "{\"addresses\":[\"host.example\"]}", "not json").forEach { body ->
            val exception = assertFailsWith<IllegalArgumentException> { PicaInitAddresses.parse(body) }
            assertTrue(exception.message!!.contains("未获取到分流地址"))
        }
    }

    @Test
    fun ipv6EquivalentSpellingsAreDeduplicated() {
        assertEquals(1, PicaInitAddresses.parse("""{"addresses":["2001:db8::1","2001:0db8:0:0:0:0:0:1"]}""").size)
    }

    @Test
    fun initUsesConfiguredProxyWithoutSignedHeaders() = runTest {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().body("""{"addresses":["104.19.53.76"]}""").build())
            val result = PicaRouteTransport().fetchApiAddresses("http://127.0.0.1:${proxy.port}")
            assertEquals(listOf("104.19.53.76"), result)
            val request = proxy.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(request.requestLine.contains("http://68.183.234.72/init"))
            listOf("api-key", "authorization", "signature").forEach { assertNull(request.headers[it]) }
        }
    }

    @Test
    fun initHttpFailureIsNotParsedAsSuccess() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            assertFailsWith<IOException> { PicaRouteTransport(server.url("/init").toString()).fetchApiAddresses(null) }
        }
    }

    @Test
    fun imageProbeAcceptsErrorStatusAndDoesNotFollowRedirect() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(301).addHeader("Location", server.url("/redirected")).build())
            assertTrue(PicaRouteTransport().measureImageHost(server.url("/").toString()) >= 0)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse.Builder().code(503).build())
            assertTrue(PicaRouteTransport().measureImageHost(server.url("/").toString()) >= 0)
        }
    }
}
