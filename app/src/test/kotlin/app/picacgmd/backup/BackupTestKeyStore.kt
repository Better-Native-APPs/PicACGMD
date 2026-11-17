package app.picacgmd.backup

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** Only the Android Keystore provider is faked; production serialization and AES-GCM use real JCA. */
internal object BackupTestKeyStore {
    val keys = ConcurrentHashMap<String, Key>()

    fun install() {
        keys.clear()
        check(Security.addProvider(TestProvider()) != -1) { "The backup test Keystore provider is already installed" }
    }

    fun remove() {
        keys.clear()
        Security.removeProvider("AndroidKeyStore")
    }

    private class TestProvider : Provider("AndroidKeyStore", 1.0, "In-memory backup test keys") {
        init {
            put("KeyStore.AndroidKeyStore", BackupKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", BackupKeyGeneratorSpi::class.java.name)
        }
    }
}

class BackupKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = BackupTestKeyStore.keys[alias]
    override fun engineDeleteEntry(alias: String) { BackupTestKeyStore.keys.remove(alias) }
    override fun engineContainsAlias(alias: String): Boolean = BackupTestKeyStore.keys.containsKey(alias)
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(BackupTestKeyStore.keys.keys)
    override fun engineSize(): Int = BackupTestKeyStore.keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(certificate: Certificate): String? = null
    override fun engineGetCreationDate(alias: String): Date? = if (engineContainsAlias(alias)) Date(0) else null
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        BackupTestKeyStore.keys[alias] = key
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        throw UnsupportedOperationException("Only generated secret keys are used by these tests")
    }
    override fun engineSetCertificateEntry(alias: String, certificate: Certificate) {
        throw UnsupportedOperationException("Certificates are not used by these tests")
    }
}

class BackupKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null
    private var keySize = 256
    private var random = SecureRandom()

    override fun engineInit(random: SecureRandom?) { this.random = random ?: SecureRandom() }
    override fun engineInit(keySize: Int, random: SecureRandom?) {
        this.keySize = keySize
        engineInit(random)
    }
    override fun engineInit(parameters: AlgorithmParameterSpec, random: SecureRandom?) {
        val specification = parameters as KeyGenParameterSpec
        alias = specification.keystoreAlias
        engineInit(specification.keySize, random)
    }
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES", "SunJCE").apply {
        init(keySize, random)
    }.generateKey().also { BackupTestKeyStore.keys[checkNotNull(alias)] = it }
}
