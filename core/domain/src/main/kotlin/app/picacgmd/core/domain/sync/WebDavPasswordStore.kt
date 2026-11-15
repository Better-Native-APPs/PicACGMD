package app.picacgmd.core.domain.sync

import android.content.Context
import app.picacgmd.core.domain.session.KeystoreEncryptedFile
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WebDavPassword(val accountKey: String, val password: String) {
    override fun toString(): String = "WebDavPassword()"
}

interface WebDavPasswordStore {
    suspend fun read(): WebDavPassword?
    suspend fun save(password: WebDavPassword)
    suspend fun clear()
}

class KeystoreWebDavPasswordStore(context: Context) : WebDavPasswordStore {
    private val file = KeystoreEncryptedFile(context, "webdav_credentials", "app.picacgmd.webdav.credentials")

    override suspend fun read(): WebDavPassword? = protect {
        file.read()?.let { Json.decodeFromString<WebDavPassword>(it.toString(Charsets.UTF_8)) }
    }
    override suspend fun save(password: WebDavPassword) = protect {
        file.save(Json.encodeToString(password).toByteArray(Charsets.UTF_8))
    }
    override suspend fun clear() = protect { file.clear() }

    private suspend fun <T> protect(block: suspend () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw WebDavException(WebDavError.STORAGE)
    }
}
