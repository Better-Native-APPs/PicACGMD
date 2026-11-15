package app.picacgmd.core.network.webdav

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** An isolated client: system proxy and DNS, no API interceptors, cookies, cache, or logging. */
class OkHttpWebDavClient private constructor(private val client: OkHttpClient) : WebDavTransport, AutoCloseable {
    constructor() : this(newClient())

    internal constructor(socketFactory: SSLSocketFactory, trustManager: X509TrustManager, timeoutMillis: Long = 60_000) :
        this(newClient().newBuilder().sslSocketFactory(socketFactory, trustManager)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build())

    override suspend fun testConnection(account: WebDavAccount): WebDavResult<WebDavConnection> = result {
        val parent = perform(account, "PROPFIND", account.endpoint.collection, PROPFIND_BODY, depth = true)
        requireDav(parent)
        val created = perform(account, "MKCOL", account.endpoint.folder, EMPTY_BODY)
        when (created.code) {
            201 -> WebDavConnection(folderCreated = true)
            405 -> {
                requireDav(perform(account, "PROPFIND", account.endpoint.folder, PROPFIND_BODY, depth = true))
                WebDavConnection(folderCreated = false)
            }
            else -> throw statusError(created.code)
        }
    }

    override suspend fun getHistory(account: WebDavAccount): WebDavResult<WebDavRemoteFile> = result {
        val response = perform(account, "GET", account.endpoint.history, readBody = true)
        when (response.code) {
            200 -> WebDavRemoteFile(response.content!!, WebDavRevision.Existing(strongEtag(response.etag)))
            404 -> WebDavRemoteFile(null, WebDavRevision.Missing)
            206 -> throw WebDavException(WebDavError.DAMAGED_FILE)
            else -> throw statusError(response.code)
        }
    }

    override suspend fun putHistory(
        account: WebDavAccount,
        content: ByteArray,
        revision: WebDavRevision,
    ): WebDavResult<Unit> = result {
        if (content.size > MAX_HISTORY_BYTES) throw WebDavException(WebDavError.FILE_TOO_LARGE)
        val condition = when (revision) {
            WebDavRevision.Missing -> "If-None-Match" to "*"
            is WebDavRevision.Existing -> strongEtag(revision.etag)?.let { "If-Match" to it }
        }
        val response = perform(account, "PUT", account.endpoint.history, content.toRequestBody(JSON), condition = condition)
        if (response.code !in setOf(200, 201, 204)) throw statusError(response.code)
    }

    private suspend fun perform(
        account: WebDavAccount,
        method: String,
        url: HttpUrl,
        body: RequestBody? = null,
        depth: Boolean = false,
        readBody: Boolean = false,
        condition: Pair<String, String>? = null,
    ): Reply {
        if (account.username.isBlank() || account.username.any { it == ':' || it.isISOControl() }) {
            throw WebDavException(WebDavError.INVALID_USERNAME)
        }
        if (account.password.isEmpty()) throw WebDavException(WebDavError.PASSWORD_REQUIRED)
        var target = url
        var authenticated = false
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(target).method(method, body).apply {
                if (depth) header("Depth", "0")
                if (authenticated) header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
                condition?.let { header(it.first, it.second) }
            }.build()
            val response = exchange(request, readBody)
            if (response.code == 401) {
                if (!authenticated && response.basicChallenge) {
                    authenticated = true
                    continue
                }
                throw WebDavException(WebDavError.AUTHENTICATION, 401)
            }
            if (response.code in REDIRECTS) {
                val next = response.location?.let(target::resolve)
                if (next == null || !next.isHttps || next.host != url.host || next.port != url.port ||
                    next.username.isNotEmpty() || next.password.isNotEmpty() || ++redirects > MAX_REDIRECTS ||
                    response.code == 303 && method != "GET"
                ) throw WebDavException(WebDavError.UNSAFE_REDIRECT)
                target = next
                continue
            }
            return response
        }
    }

    private suspend fun exchange(request: Request, readBody: Boolean): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(transportError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val content = if (readBody && it.code == 200) {
                            val body = it.body
                            if (body.contentLength() > MAX_HISTORY_BYTES) throw WebDavException(WebDavError.FILE_TOO_LARGE)
                            val buffer = Buffer()
                            val source = body.source()
                            while (true) {
                                val read = source.read(buffer, minOf(8_192L, MAX_HISTORY_BYTES + 1L - buffer.size))
                                if (read == -1L) break
                                if (buffer.size > MAX_HISTORY_BYTES) throw WebDavException(WebDavError.FILE_TOO_LARGE)
                            }
                            buffer.readByteArray()
                        } else null
                        Reply(it.code, it.header("ETag"), it.header("Location"),
                            it.challenges().any { challenge -> challenge.scheme.equals("Basic", ignoreCase = true) }, content)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (exception: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (exception is WebDavException) exception else transportError(exception),
                    )
                }
            }
        })
    }

    private fun requireDav(reply: Reply) {
        // Multi-Status is the DAV-specific success response for a depth-zero PROPFIND.
        if (reply.code == 207) return
        if (reply.code in 200..299 || reply.code == 405 || reply.code == 501) {
            throw WebDavException(WebDavError.NOT_WEBDAV)
        }
        throw statusError(reply.code)
    }

    private fun strongEtag(etag: String?): String? {
        // Weak or absent ETags cannot protect concurrent uploads; simultaneous devices can overwrite each other.
        return etag?.takeUnless { it.startsWith("W/") }
    }

    private suspend fun <T> result(block: suspend () -> T): WebDavResult<T> = try {
        WebDavResult.Success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: WebDavException) {
        WebDavResult.Failure(failure.error, failure.httpStatus)
    } catch (_: Exception) {
        WebDavResult.Failure(WebDavError.CONNECTION)
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private class Reply(
        val code: Int,
        val etag: String?,
        val location: String?,
        val basicChallenge: Boolean,
        val content: ByteArray?,
    )

    companion object {
        const val MAX_HISTORY_BYTES = 20 * 1_024 * 1_024
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())

        private fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

        private fun transportError(exception: Exception): WebDavException = WebDavException(when (exception) {
            is SSLException -> WebDavError.TLS
            is SocketTimeoutException, is InterruptedIOException -> WebDavError.TIMEOUT
            else -> WebDavError.CONNECTION
        })

        private fun statusError(code: Int): WebDavException = WebDavException(when (code) {
            401 -> WebDavError.AUTHENTICATION
            403 -> WebDavError.PERMISSION
            404, 409 -> WebDavError.PARENT_MISSING
            405, 501 -> WebDavError.NOT_WEBDAV
            412 -> WebDavError.CHANGED_ELSEWHERE
            507 -> WebDavError.QUOTA
            else -> WebDavError.HTTP_ERROR
        }, code)
    }
}
