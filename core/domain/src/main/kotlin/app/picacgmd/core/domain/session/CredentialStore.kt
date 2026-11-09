package app.picacgmd.core.domain.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Credentials(val email: String, val password: String) {
    override fun toString(): String = "Credentials(hasEmail=${email.isNotBlank()})"
}

interface CredentialStore {
    suspend fun read(): Credentials?
    suspend fun save(credentials: Credentials)
    suspend fun clear()
}

/** Only ciphertext is stored on disk; the AES key never leaves AndroidKeyStore. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "account_credentials"))
    private val mutex = Mutex()

    override suspend fun read(): Credentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock null
            val (iv, encrypted) = DataInputStream(ByteArrayInputStream(file.readFully())).use { input ->
                if (input.readInt() != FILE_VERSION) throw IOException("读取凭据失败：版本不支持")
                val ivSize = input.readInt()
                if (ivSize != IV_BYTES) throw IOException("读取凭据失败：格式错误")
                val iv = ByteArray(ivSize).also(input::readFully)
                iv to input.readBytes()
            }
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw IOException("读取凭据失败：密钥不可用")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            Json.decodeFromString<Credentials>(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        }
    }

    override suspend fun save(credentials: Credentials) = withContext(Dispatchers.IO) {
        require(credentials.email.isNotBlank() && credentials.password.isNotEmpty()) { "登录账号和密码不能为空" }
        mutex.withLock {
            val key = (keyStore().getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                }.generateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(Json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(FILE_VERSION)
                    output.writeInt(cipher.iv.size)
                    output.write(cipher.iv)
                    output.write(encrypted)
                }
            }.toByteArray()
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (exception: Exception) {
                file.failWrite(output)
                throw exception
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            keyStore().deleteEntry(KEY_ALIAS)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "app.picacgmd.account.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
