package dsh.transport

import kotlinx.serialization.Serializable

/**
 * A DSH server the app can connect to.
 * @property baseUrl document base, always ending in `/` — e.g. `https://host.example/dsh/`.
 * @property cookie `name=value` of the `dsh-auth-*` cookie obtained by exchanging the launch token.
 */
@Serializable
data class Environment(
    val id: String,
    val name: String,
    val baseUrl: String,
    val cookie: String? = null,
    val lastUsedAt: Long = 0,
) {
    val apiUrl: String get() = baseUrl + "api/"
    val muxUrl: String
        get() = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        } + "api/remote.mux"

    companion object {
        /**
         * Accepts what users paste: `host/dsh`, `https://host/dsh/`, or a full launch URL
         * `https://host/dsh/?token=XYZ`. Returns normalized base URL and any token found in the query.
         */
        fun parseInput(raw: String): Pair<String, String?> {
            var s = raw.trim()
            if (!s.contains("://")) s = "https://$s"
            var token: String? = null
            val q = s.indexOf('?')
            if (q >= 0) {
                val query = s.substring(q + 1).substringBefore('#')
                token = query.split('&').firstOrNull { it.startsWith("token=") }?.removePrefix("token=")?.let(::percentDecode)
                s = s.substring(0, q)
            }
            s = s.substringBefore('#')
            if (!s.endsWith("/")) s += "/"
            return s to token
        }

        private fun percentDecode(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    out.append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3
                } else { out.append(if (c == '+') ' ' else c); i++ }
            }
            return out.toString()
        }
    }
}
