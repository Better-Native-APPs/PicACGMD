package app.picacgmd.core.network

import java.net.InetAddress

/** Never resolves a hostname. Canonical values also deduplicate equivalent IPv6 spellings. */
object IpLiteral {
    fun normalize(value: String): String = parse(value).hostAddress!!

    fun normalizeOrNull(value: String): String? = try {
        normalize(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    internal fun parse(value: String): InetAddress {
        val literal = value.trim().removeSurrounding("[", "]")
        val ipv4 = literal.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
            literal.split('.').all { it.toInt() in 0..255 }
        val ipv6 = ':' in literal && literal.matches(Regex("[0-9a-fA-F:.]+"))
        require(ipv4 || ipv6) { "分流地址必须是 IPv4 或 IPv6 地址" }
        return try {
            // Only numeric syntax reaches getByName, so this cannot perform an external DNS lookup.
            InetAddress.getByName(literal)
        } catch (_: java.net.UnknownHostException) {
            throw IllegalArgumentException("分流地址必须是 IPv4 或 IPv6 地址")
        }
    }
}
