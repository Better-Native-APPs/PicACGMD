package app.picacgmd.core.network

import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.Headers
import okhttp3.HttpUrl

/** Public Android protocol identifiers. These are not application credentials. */
object PicaProtocol {
    const val API_HOST = "picaapi.picacomic.com"
    const val BASE_URL = "https://$API_HOST/"
    const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    const val ACCEPT = "application/vnd.picacomic.com.v1+json"
    const val APP_CHANNEL = "2"
    const val APP_VERSION = "2.2.1.2.3.3"
    const val APP_UUID = "defaultUuid"
    const val APP_PLATFORM = "android"
    const val APP_BUILD_VERSION = "44"
    const val CONTENT_TYPE = "application/json; charset=UTF-8"
    const val USER_AGENT = "okhttp/3.8.1"
    internal const val SIGNING_SECRET = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
}

enum class ImageQuality(val value: String) {
    ORIGINAL("original"), LOW("low"), MEDIUM("medium"), HIGH("high"),
}

enum class ComicSort(val value: String) {
    DEFAULT("ua"), NEWEST("dd"), OLDEST("da"), MOST_LIKED("ld"), MOST_VIEWED("vd"),
}

enum class LeaderboardType(val value: String) {
    H24("H24"), D7("D7"), D30("D30"),
}

class PicaSigner {
    /** [pathAndQuery] must be the encoded request URI, not a decoded path. */
    fun sign(pathAndQuery: String, method: String, time: String, nonce: String): String {
        val raw = (pathAndQuery.removePrefix("/") + time + nonce + method + PicaProtocol.API_KEY)
            .lowercase(Locale.ROOT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(PicaProtocol.SIGNING_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

class PicaHeaderBuilder(
    private val signer: PicaSigner = PicaSigner(),
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val nonceProvider: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val appUuid: String = PicaProtocol.APP_UUID,
) {
    fun build(
        url: HttpUrl,
        method: String,
        token: String? = null,
        quality: ImageQuality = ImageQuality.ORIGINAL,
    ): Headers {
        val time = epochSeconds().toString()
        val nonce = nonceProvider()
        val requestUri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return Headers.Builder()
            .add("api-key", PicaProtocol.API_KEY)
            .add("accept", PicaProtocol.ACCEPT)
            .add("app-channel", PicaProtocol.APP_CHANNEL)
            .add("time", time)
            .add("nonce", nonce)
            .add("signature", signer.sign(requestUri, method, time, nonce))
            .add("app-version", PicaProtocol.APP_VERSION)
            .add("app-uuid", appUuid)
            .add("app-platform", PicaProtocol.APP_PLATFORM)
            .add("app-build-version", PicaProtocol.APP_BUILD_VERSION)
            .add("Content-Type", PicaProtocol.CONTENT_TYPE)
            .add("User-Agent", PicaProtocol.USER_AGENT)
            .add("authorization", token.orEmpty())
            .add("image-quality", quality.value)
            .build()
    }
}
