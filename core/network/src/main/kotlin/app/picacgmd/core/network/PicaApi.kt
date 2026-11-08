package app.picacgmd.core.network

import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * PicACG-only API. Token persistence and re-login belong to the caller; the provider is read on
 * every request. Redirects are disabled even for injected clients to keep credentials on this host.
 */
class PicaApi(
    private val tokenProvider: () -> String? = { null },
    baseUrl: String = PicaProtocol.BASE_URL,
    client: OkHttpClient = PicaClientFactory.apiClient(),
    private val headerBuilder: PicaHeaderBuilder = PicaHeaderBuilder(),
    private val json: Json = PicaJson,
) {
    private val baseUrl = baseUrl.toHttpUrl().also {
        require(it.encodedPath.endsWith('/') && it.query == null && it.fragment == null) {
            "API 基础地址必须以 / 结尾，且不包含查询或片段"
        }
    }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val parser = PicaEnvelopeParser(json)

    suspend fun login(email: String, password: String): PicaResult<String> = fetch(
        "POST", listOf("auth", "sign-in"), key = "token",
        body = buildJsonObject { put("email", email); put("password", password) },
    )

    suspend fun register(registration: RegisterDto): PicaResult<Unit> = mutate(
        "POST", listOf("auth", "register"), json.encodeToJsonElement(registration),
    )

    suspend fun forgotPassword(email: String): PicaResult<ForgotPasswordResult> = fetch(
        "POST", listOf("auth", "forgot-password"),
        body = buildJsonObject { put("email", email) },
    )

    suspend fun resetPassword(email: String, questionNo: Int, answer: String): PicaResult<ResetPasswordResult> = fetch(
        "POST", listOf("auth", "reset-password"),
        body = buildJsonObject { put("email", email); put("questionNo", questionNo); put("answer", answer) },
    )

    suspend fun userProfile(): PicaResult<UserProfile> = fetch("GET", listOf("users", "profile"), key = "user")

    suspend fun punchIn(): PicaResult<PunchStatus> = fetch("POST", listOf("users", "punch-in"), key = "res")

    /** The documented editable profile field is slogan. */
    suspend fun updateProfile(slogan: String): PicaResult<Unit> = mutate(
        "PUT", listOf("users", "profile"), buildJsonObject { put("slogan", slogan) },
    )

    suspend fun updateSlogan(slogan: String): PicaResult<Unit> = updateProfile(slogan)

    suspend fun updatePassword(oldPassword: String, newPassword: String): PicaResult<Unit> = mutate(
        "PUT", listOf("users", "password"),
        buildJsonObject { put("old_password", oldPassword); put("new_password", newPassword) },
    )

    /** Caller supplies an already resized 200×200 JPEG as a data URI. */
    suspend fun updateAvatar(dataUri: String): PicaResult<Unit> = mutate(
        "PUT", listOf("users", "avatar"), buildJsonObject { put("avatar", dataUri) },
    )

    suspend fun updateAvatar(jpegBytes: ByteArray): PicaResult<Unit> =
        updateAvatar("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes))

    suspend fun categories(): PicaResult<List<Category>> = fetch("GET", listOf("categories"), key = "categories")

    suspend fun announcements(page: Int): PicaResult<Page<Announcement>> =
        fetch("GET", listOf("announcements"), key = "announcements", query = pageQuery(page))

    suspend fun comics(
        category: String? = null,
        tag: String? = null,
        author: String? = null,
        creatorId: String? = null,
        chineseTeam: String? = null,
        sort: ComicSort = ComicSort.NEWEST,
        page: Int = 1,
    ): PicaResult<Page<ComicSimple>> = fetch(
        "GET", listOf("comics"), key = "comics",
        query = buildList {
            listOf("c" to category, "t" to tag, "a" to author, "ca" to creatorId, "ct" to chineseTeam)
                .forEach { (key, value) -> if (!value.isNullOrEmpty()) add(key to value) }
            add("s" to sort.value)
            add("page" to page.toString())
        },
    )

    suspend fun searchComics(
        keyword: String,
        sort: ComicSort = ComicSort.NEWEST,
        page: Int = 1,
        categories: List<String> = emptyList(),
    ): PicaResult<Page<ComicSimple>> = fetch(
        "POST", listOf("comics", "advanced-search"), key = "comics", query = pageQuery(page),
        body = buildJsonObject {
            put("keyword", keyword)
            put("sort", sort.value)
            put("categories", buildJsonArray { categories.forEach { add(json.encodeToJsonElement(it)) } })
        },
    )

    suspend fun comicInfo(comicId: String): PicaResult<ComicInfo> =
        fetch("GET", listOf("comics", comicId), key = "comic")

    suspend fun comicEpPage(comicId: String, page: Int = 1): PicaResult<Page<Ep>> =
        fetch("GET", listOf("comics", comicId, "eps"), key = "eps", query = pageQuery(page))

    suspend fun comicPicturePageWithQuality(
        comicId: String,
        epOrder: Int,
        page: Int = 1,
        quality: ImageQuality = ImageQuality.ORIGINAL,
    ): PicaResult<PicturePage> = fetch(
        "GET", listOf("comics", comicId, "order", epOrder.toString(), "pages"),
        query = pageQuery(page), quality = quality,
    )

    suspend fun recommendation(comicId: String): PicaResult<List<ComicSimple>> =
        fetch("GET", listOf("comics", comicId, "recommendation"), key = "comics")

    suspend fun switchLike(comicId: String): PicaResult<LikeAction> =
        fetch("POST", listOf("comics", comicId, "like"), key = "action")

    suspend fun switchFavourite(comicId: String): PicaResult<String> =
        fetch("POST", listOf("comics", comicId, "favourite"), key = "action")

    suspend fun favouriteComics(sort: ComicSort = ComicSort.NEWEST, page: Int = 1): PicaResult<Page<ComicSimple>> =
        fetch("GET", listOf("users", "favourite"), key = "comics", query = listOf("s" to sort.value) + pageQuery(page))

    suspend fun collections(): PicaResult<List<ComicCollection>> =
        fetch("GET", listOf("collections"), key = "collections")

    suspend fun hotKeywords(): PicaResult<List<String>> = fetch("GET", listOf("keywords"), key = "keywords")

    suspend fun leaderboard(type: LeaderboardType = LeaderboardType.H24): PicaResult<List<ComicSimple>> =
        fetch("GET", listOf("comics", "leaderboard"), key = "comics", query = listOf("tt" to type.value, "ct" to "VC"))

    suspend fun leaderboardOfKnight(): PicaResult<List<Knight>> =
        fetch("GET", listOf("comics", "knight-leaderboard"), key = "users")

    suspend fun randomComics(): PicaResult<List<ComicSimple>> = fetch("GET", listOf("comics", "random"), key = "comics")

    suspend fun comments(comicId: String, page: Int = 1): PicaResult<CommentsPage> =
        fetch("GET", listOf("comics", comicId, "comments"), query = pageQuery(page))

    suspend fun commentChildren(commentId: String, page: Int = 1): PicaResult<Page<Comment>> =
        fetch("GET", listOf("comments", commentId, "childrens"), key = "comments", query = pageQuery(page))

    suspend fun postComment(comicId: String, content: String): PicaResult<Unit> =
        mutate("POST", listOf("comics", comicId, "comments"), contentBody(content))

    suspend fun postChildComment(commentId: String, content: String): PicaResult<Unit> =
        mutate("POST", listOf("comments", commentId), contentBody(content))

    suspend fun switchLikeComment(commentId: String): PicaResult<String> =
        fetch("POST", listOf("comments", commentId, "like"), key = "action")

    /** Server-side moderation endpoint; ordinary accounts may receive an authorization error. */
    suspend fun hideComment(commentId: String): PicaResult<Unit> =
        mutate("POST", listOf("comments", commentId, "delete"))

    suspend fun reportComment(commentId: String): PicaResult<Unit> =
        mutate("POST", listOf("comments", commentId, "report"), buildJsonObject { })

    suspend fun myComments(page: Int = 1): PicaResult<Page<MyComment>> =
        fetch("GET", listOf("users", "my-comments"), key = "comments", query = pageQuery(page))

    suspend fun games(page: Int = 1): PicaResult<Page<GameSimple>> =
        fetch("GET", listOf("games"), key = "games", query = pageQuery(page))

    suspend fun game(gameId: String): PicaResult<GameInfo> = fetch("GET", listOf("games", gameId), key = "game")

    suspend fun switchLikeGame(gameId: String): PicaResult<LikeAction> =
        fetch("POST", listOf("games", gameId, "like"), key = "action", body = buildJsonObject { })

    suspend fun gameComments(gameId: String, page: Int = 1): PicaResult<CommentsPage> =
        fetch("GET", listOf("games", gameId, "comments"), query = pageQuery(page))

    suspend fun postGameComment(gameId: String, content: String): PicaResult<Unit> =
        mutate("POST", listOf("games", gameId, "comments"), contentBody(content))

    // Game replies and comic replies share the comments API (protocol §3 / pica-go client).
    suspend fun gameCommentChildren(commentId: String, page: Int = 1): PicaResult<Page<Comment>> =
        commentChildren(commentId, page)

    suspend fun postGameChildComment(commentId: String, content: String): PicaResult<Unit> =
        postChildComment(commentId, content)

    suspend fun switchLikeGameComment(commentId: String): PicaResult<String> = switchLikeComment(commentId)

    private fun contentBody(content: String): JsonObject = buildJsonObject { put("content", content) }

    private fun pageQuery(page: Int): List<Pair<String, String>> = listOf("page" to page.toString())

    private suspend inline fun <reified T> fetch(
        method: String,
        path: List<String>,
        key: String? = null,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        quality: ImageQuality = ImageQuality.ORIGINAL,
    ): PicaResult<T> = request(method, path, query, body, quality) { data ->
        val value = if (key == null) data else {
            (data as? JsonObject)?.get(key) ?: throw SerializationException("Missing response field: $key")
        }
        json.decodeFromJsonElement<T>(value)
    }

    private suspend fun mutate(method: String, path: List<String>, body: JsonElement? = null): PicaResult<Unit> =
        request(method, path, emptyList(), body, ImageQuality.ORIGINAL) { }

    private suspend fun <T> request(
        method: String,
        path: List<String>,
        query: List<Pair<String, String>>,
        body: JsonElement?,
        quality: ImageQuality,
        decode: (JsonElement) -> T,
    ): PicaResult<T> {
        val url = baseUrl.newBuilder().apply {
            path.forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val requestBody = when (method) {
            "GET" -> null
            else -> (body?.toString().orEmpty()).toRequestBody(PicaProtocol.CONTENT_TYPE.toMediaType())
        }
        val request = Request.Builder().url(url)
            .headers(headerBuilder.build(url, method, tokenProvider(), quality))
            .method(method, requestBody).build()
        return try {
            val response = client.newCall(request).awaitBody()
            when (val result = parser.parse(response.body, response.status)) {
                is PicaResult.Failure -> result
                is PicaResult.Success -> try {
                    PicaResult.Success(decode(result.data))
                } catch (exception: SerializationException) {
                    PicaResult.Failure(PicaDecodingException(exception))
                }
            }
        } catch (exception: IOException) {
            PicaResult.Failure(PicaTransportException(exception))
        }
    }
}

private data class ResponseBody(val status: Int, val body: String)

/** Read and close bodies on OkHttp's worker, and cancel the call when its coroutine is cancelled. */
private suspend fun Call.awaitBody(): ResponseBody = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { ResponseBody(it.code, it.body.string()) }
                continuation.resume(result)
            } catch (exception: IOException) {
                continuation.resumeWithException(exception)
            }
        }
    })
}
