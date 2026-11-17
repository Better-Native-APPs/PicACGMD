package app.picacgmd.core.network.webdav

import java.io.IOException
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class WebDavError {
    INVALID_ADDRESS, HTTPS_REQUIRED, INVALID_FOLDER, INVALID_USERNAME, PASSWORD_REQUIRED,
    CONNECTION, TIMEOUT, TLS, AUTHENTICATION, PERMISSION, PARENT_MISSING, QUOTA,
    NOT_WEBDAV, FILE_TOO_LARGE, NEWER_FORMAT, DAMAGED_FILE, INVALID_TIMESTAMP, CHANGED_ELSEWHERE,
    UNSAFE_REDIRECT, HTTP_ERROR, STORAGE,
}

sealed interface WebDavResult<out T> {
    data class Success<T>(val value: T) : WebDavResult<T>
    data class Failure(val error: WebDavError, val httpStatus: Int? = null) : WebDavResult<Nothing>

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw WebDavException(error, httpStatus)
    }
}

/** Contains no response body, URL, credentials, or underlying exception text. */
class WebDavException(val error: WebDavError, val httpStatus: Int? = null) :
    IOException("WebDAV ${error.name}")

class WebDavEndpoint private constructor(
    val collection: HttpUrl,
    val folder: HttpUrl,
    val history: HttpUrl,
) {
    override fun toString(): String = "WebDavEndpoint()"

    companion object {
        const val DEFAULT_FOLDER = "PicACGMD"

        fun parse(address: String, folderName: String = DEFAULT_FOLDER): WebDavResult<WebDavEndpoint> {
            val input = address.trim()
            val uri = try { URI(input) } catch (_: Exception) {
                return WebDavResult.Failure(WebDavError.INVALID_ADDRESS)
            }
            if (uri.scheme.equals("http", ignoreCase = true)) {
                return WebDavResult.Failure(WebDavError.HTTPS_REQUIRED)
            }
            val url = input.toHttpUrlOrNull()
            if (!input.startsWith("https://", ignoreCase = true) || url == null ||
                !url.isHttps || uri.rawAuthority == null || uri.rawUserInfo != null ||
                url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedQuery != null || url.fragment != null ||
                input.any { it.isWhitespace() || it.isISOControl() || it == '\\' }
            ) return WebDavResult.Failure(WebDavError.INVALID_ADDRESS)

            val name = folderName.trim()
            if (name.isEmpty() || name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it.isISOControl() }
            ) return WebDavResult.Failure(WebDavError.INVALID_FOLDER)

            val collection = if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
            val folder = collection.newBuilder().addPathSegment(name).addPathSegment("").build()
            return WebDavResult.Success(WebDavEndpoint(collection, folder, folder.resolve("history.json")!!))
        }
    }
}

/** Short-lived request credentials. Diagnostics never expose either credential. */
class WebDavAccount(val endpoint: WebDavEndpoint, val username: String, val password: String) {
    override fun toString(): String = "WebDavAccount()"
}

data class WebDavConnection(val folderCreated: Boolean)

sealed interface WebDavRevision {
    data object Missing : WebDavRevision
    data class Existing(val etag: String?) : WebDavRevision
}

data class WebDavRemoteFile(val content: ByteArray?, val revision: WebDavRevision)

interface WebDavTransport {
    suspend fun testConnection(account: WebDavAccount): WebDavResult<WebDavConnection>
    suspend fun getHistory(account: WebDavAccount): WebDavResult<WebDavRemoteFile>
    suspend fun putHistory(account: WebDavAccount, content: ByteArray, revision: WebDavRevision): WebDavResult<Unit>
}
