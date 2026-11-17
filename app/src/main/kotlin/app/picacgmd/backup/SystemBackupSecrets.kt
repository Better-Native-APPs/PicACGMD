package app.picacgmd.backup

import android.content.Context
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.Credentials
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.sync.KeystoreWebDavPasswordStore
import app.picacgmd.core.domain.sync.WebDavPassword
import app.picacgmd.core.domain.sync.WebDavPasswordStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Decrypted bytes cross this boundary only while a protected backup or restore is in progress. */
internal class SystemBackupSecretStore(
    val name: String,
    val read: suspend () -> ByteArray?,
    val save: suspend (ByteArray) -> Unit,
    val clear: suspend () -> Unit,
) {
    companion object {
        fun credentials(store: CredentialStore) = SystemBackupSecretStore(
            name = "account_credentials",
            read = { store.read()?.let { Json.encodeToString(it).toByteArray(Charsets.UTF_8) } },
            save = { store.save(Json.decodeFromString<Credentials>(it.toString(Charsets.UTF_8))) },
            clear = store::clear,
        )

        fun webDavPassword(store: WebDavPasswordStore) = SystemBackupSecretStore(
            name = "webdav_credentials",
            read = { store.read()?.let { Json.encodeToString(it).toByteArray(Charsets.UTF_8) } },
            save = { store.save(Json.decodeFromString<WebDavPassword>(it.toString(Charsets.UTF_8))) },
            clear = store::clear,
        )
    }
}

/** Register each encrypted store here; unregistered stores are neither backed up nor restored. */
internal fun systemBackupSecretStores(context: Context): List<SystemBackupSecretStore> = listOf(
    SystemBackupSecretStore.credentials(KeystoreCredentialStore(context)),
    SystemBackupSecretStore.webDavPassword(KeystoreWebDavPasswordStore(context)),
)

@Serializable
internal data class SystemBackupSecrets(
    val accountEmail: String = "",
    val authToken: String? = null,
    val authTokenUpdatedAt: Long? = null,
    val stores: Map<String, ByteArray> = emptyMap(),
)
