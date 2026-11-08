package app.picacgmd.core.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient

sealed interface ApiRoute {
    data object None : ApiRoute
    data class Address(val ip: String) : ApiRoute
}

/** Changes only DNS resolution: the HTTPS URL, certificate identity, Host and SNI stay unchanged. */
class PicaDns(
    route: ApiRoute = ApiRoute.None,
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {
    private val address: InetAddress? = when (route) {
        ApiRoute.None -> null
        is ApiRoute.Address -> InetAddress.getByAddress(PicaProtocol.API_HOST, IpLiteral.parse(route.ip).address)
    }

    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.equals(PicaProtocol.API_HOST, ignoreCase = true) && address != null) {
            listOf(address)
        } else {
            systemDns.lookup(hostname)
        }

}

data class PicaNetworkConfig(
    val apiRoute: ApiRoute = ApiRoute.None,
    val proxyUrl: String? = null,
)

object PicaClientFactory {
    fun apiClient(config: PicaNetworkConfig = PicaNetworkConfig()): OkHttpClient =
        builder(config).dns(PicaDns(config.apiRoute)).build()

    /**
     * Independent transport: never attaches API authentication or signing headers, so it may follow
     * redirects. The storage hosts 301 to other hosts (for example img.picacomic.com).
     */
    fun imageClient(config: PicaNetworkConfig = PicaNetworkConfig()): OkHttpClient =
        builder(config).followRedirects(true).followSslRedirects(true).build()

    private fun builder(config: PicaNetworkConfig): OkHttpClient.Builder = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        // OkHttp's socket read timeout also applies during the TLS handshake.
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(32, 10, TimeUnit.SECONDS))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .proxy(config.proxyUrl?.takeIf { it.isNotBlank() }?.let(::parseProxy))
        // Toggle/POST operations must never be automatically replayed.
        .retryOnConnectionFailure(false)
        // In particular, do not forward signed API headers to a redirected host.
        .followRedirects(false)
        .followSslRedirects(false)

    fun parseProxy(url: String): Proxy {
        val uri = URI(url)
        val type = when (uri.scheme?.lowercase()) {
            "http" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalArgumentException("代理仅支持 http:// 或 socks5://")
        }
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "代理必须包含主机和端口" }
        require(uri.rawUserInfo == null) { "此代理配置不支持内嵌账号密码" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "代理地址不能包含路径" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "代理地址不能包含查询或片段" }
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }
}
