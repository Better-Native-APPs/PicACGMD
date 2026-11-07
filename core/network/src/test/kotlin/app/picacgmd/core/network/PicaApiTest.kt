package app.picacgmd.core.network

import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class PicaApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PicaApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = PicaApi(
            tokenProvider = { "test-token" },
            baseUrl = server.url("/").toString(),
            headerBuilder = PicaHeaderBuilder(epochSeconds = { 1582250000 }, nonceProvider = { NONCE }),
        )
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun loginSendsAllRequiredHeadersAndDocumentedSignature() = runTest {
        enqueue("""{"token":"new-token"}""")
        assertEquals("new-token", api.login("name", "password").getOrThrow())
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/sign-in", request.url.encodedPath)
        assertEquals("5e0d8becedd8af6302e0c52efc43ad7040ff36feff68b2983c84e5141143992e", request.headers["signature"])
        mapOf(
            "api-key" to PicaProtocol.API_KEY,
            "accept" to PicaProtocol.ACCEPT,
            "app-channel" to "2",
            "time" to "1582250000",
            "nonce" to NONCE,
            "app-version" to "2.2.1.2.3.3",
            "app-uuid" to "defaultUuid",
            "app-platform" to "android",
            "app-build-version" to "44",
            "User-Agent" to "okhttp/3.8.1",
            "authorization" to "test-token",
            "image-quality" to "original",
        ).forEach { (key, expected) -> assertEquals(expected, request.headers[key], key) }
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"]?.lowercase())
        assertEquals("name", PicaJson.parseToJsonElement(request.body!!.utf8()).jsonObject["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun signsTheActualEncodedQueryAndIncludesAllComicFilters() = runTest {
        enqueue("""{"comics":{"docs":[]}}""")
        api.comics("大家都在看", "a&b", "Author", "creator", "汉化", ComicSort.MOST_LIKED, 12).getOrThrow()
        val request = takeRequest()
        assertEquals("大家都在看", request.url.queryParameter("c"))
        assertEquals("a&b", request.url.queryParameter("t"))
        assertEquals("Author", request.url.queryParameter("a"))
        assertEquals("creator", request.url.queryParameter("ca"))
        assertEquals("汉化", request.url.queryParameter("ct"))
        assertEquals("ld", request.url.queryParameter("s"))
        assertEquals("12", request.url.queryParameter("page"))
        val raw = (request.url.encodedPath.removePrefix("/") + "?" + request.url.encodedQuery +
            "1582250000" + NONCE + "GET" + PicaProtocol.API_KEY).lowercase()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(PicaProtocol.SIGNING_SECRET.toByteArray(), "HmacSHA256"))
        val expected = mac.doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, request.headers["signature"])
    }

    @Test
    fun tokenProviderIsReadForEveryRequest() = runTest {
        var token: String? = null
        val dynamicApi = PicaApi(tokenProvider = { token }, baseUrl = server.url("/").toString())
        enqueue("""{"categories":[]}""")
        enqueue("""{"categories":[]}""")
        dynamicApi.categories().getOrThrow()
        assertEquals("", takeRequest().headers["authorization"])
        token = "replacement-token"
        dynamicApi.categories().getOrThrow()
        assertEquals(token, takeRequest().headers["authorization"])
    }

    @Test
    fun successEnvelopeReturnsDecodedData() = runTest {
        enqueue("""{"user":{"_id":"u","name":"测试用户","isPunched":true}}""")
        val user = api.userProfile().getOrThrow()
        assertEquals("u", user.id)
        assertEquals(true, user.isPunched)
    }

    @Test
    fun businessErrorRetainsEnvelopeFields() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"code":400,"error":"1004","message":"请求有误","detail":{"field":"email"}}""").build())
        val exception = assertIs<PicaResult.Failure>(api.categories()).exception
        assertEquals(400, exception.code)
        assertEquals(200, exception.httpStatus)
        assertEquals("1004", exception.error)
        assertEquals("请求有误", exception.message)
        assertEquals("email", exception.detail?.jsonObject?.get("field")?.jsonPrimitive?.content)
    }

    @Test
    fun http401AndEnvelope401BothRequireRelogin() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("not JSON").build())
        server.enqueue(MockResponse.Builder().body("""{"code":401,"error":"1005","message":"token expired"}""").build())
        assertIs<UnauthorizedException>(assertIs<PicaResult.Failure>(api.categories()).exception)
        val second = assertIs<PicaResult.Failure>(api.categories())
        assertEquals("token expired", assertIs<UnauthorizedException>(second.exception).message)
        assertFailsWith<UnauthorizedException> { second.getOrThrow() }
    }

    @Test
    fun httpFailuresAndMalformedSuccessProduceTypedErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        server.enqueue(MockResponse.Builder().body("not JSON").build())
        enqueue("""{"comic":{"tags":42}}""")
        assertEquals(503, assertIs<PicaResult.Failure>(api.categories()).exception.httpStatus)
        assertIs<PicaDecodingException>(assertIs<PicaResult.Failure>(api.categories()).exception)
        assertIs<PicaDecodingException>(assertIs<PicaResult.Failure>(api.comicInfo("id")).exception)
    }

    @Test
    fun mutationAcceptsAnEnvelopeWithoutData() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"code":200,"message":"success"}""").build())
        assertEquals(Unit, api.postComment("comic", "你好").getOrThrow())
        val request = takeRequest()
        assertEquals("/comics/comic/comments", request.url.encodedPath)
        assertEquals("你好", PicaJson.parseToJsonElement(request.body!!.utf8()).jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun pictureRequestUsesSelectedQualityAndKeepsEpisodeMetadata() = runTest {
        enqueue("""{"pages":{"docs":[{"_id":"p","media":{"fileServer":"https://s2.picacomic.com","path":"p.jpg"}}]},"ep":{"order":3,"title":"第三话"}}""")
        val data = api.comicPicturePageWithQuality("comic", 3, 2, ImageQuality.HIGH).getOrThrow()
        assertEquals("p", data.pages.docs.single().id)
        assertEquals(3, data.ep?.order)
        val request = takeRequest()
        assertEquals("/comics/comic/order/3/pages", request.url.encodedPath)
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("high", request.headers["image-quality"])
    }

    @Test
    fun advancedSearchSendsCategoriesInJson() = runTest {
        enqueue("""{"comics":{"docs":[]}}""")
        api.searchComics("关键词", ComicSort.OLDEST, 4, listOf("分类一")).getOrThrow()
        val request = takeRequest()
        assertEquals("/comics/advanced-search", request.url.encodedPath)
        assertEquals("4", request.url.queryParameter("page"))
        assertEquals("""{"keyword":"关键词","sort":"da","categories":["分类一"]}""", request.body!!.utf8())
    }

    @Test
    fun rankingsAndSharedGameCommentRoutesMatchProtocol() = runTest {
        LeaderboardType.entries.forEach { type ->
            enqueue("""{"comics":[]}""")
            api.leaderboard(type).getOrThrow()
            val request = takeRequest()
            assertEquals(type.value, request.url.queryParameter("tt"))
            assertEquals("VC", request.url.queryParameter("ct"))
        }
        enqueue("""{"comments":{"docs":[{"_id":"child","_game":"g","_parent":"parent"}]}}""")
        assertEquals("g", api.gameCommentChildren("parent").getOrThrow().docs.single().gameId)
        assertEquals("/comments/parent/childrens", takeRequest().url.encodedPath)
        enqueue("{}")
        api.postGameChildComment("parent", "reply").getOrThrow()
        assertEquals("/comments/parent", takeRequest().url.encodedPath)
        enqueue("""{"action":"like"}""")
        assertEquals("like", api.switchLikeGameComment("parent").getOrThrow())
        assertEquals("/comments/parent/like", takeRequest().url.encodedPath)
    }

    @Test
    fun avatarAndPasswordUseDocumentedJsonBodies() = runTest {
        enqueue("{}")
        api.updateAvatar(byteArrayOf(1, 2, 3)).getOrThrow()
        val avatar = takeRequest()
        assertEquals("PUT", avatar.method)
        assertEquals("/users/avatar", avatar.url.encodedPath)
        assertEquals("""{"avatar":"data:image/jpeg;base64,AQID"}""", avatar.body!!.utf8())
        enqueue("{}")
        api.updatePassword("old", "new").getOrThrow()
        assertEquals("""{"old_password":"old","new_password":"new"}""", takeRequest().body!!.utf8())
    }

    @Test
    fun redirectsAreNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/must-not-follow").toString()).build())
        assertEquals(302, assertIs<PicaResult.Failure>(api.categories()).exception.httpStatus)
        assertEquals(1, server.requestCount)
    }

    private fun enqueue(data: String) {
        server.enqueue(MockResponse.Builder().body("""{"code":200,"message":"success","data":$data}""").build())
    }

    private fun takeRequest(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private companion object {
        const val NONCE = "b1ab87b4800d4d4590a11701b8551afa"
    }
}
