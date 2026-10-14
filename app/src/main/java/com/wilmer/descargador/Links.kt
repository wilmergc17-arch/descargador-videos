package com.wilmer.descargador

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

object Links {
    private val URL_IN_TEXT = Regex("""https?://\S+""")

    // Con agente de navegador de escritorio los enlaces cortos de TikTok llevan a la página del video
    // en vez de mandar a la Play Store.
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val STORES = setOf("play.google.com", "apps.apple.com", "itunes.apple.com", "market.android.com")
    private val SHORTENERS = setOf(
        "vm.tiktok.com", "vt.tiktok.com", "t.tiktok.com", "lite.tiktok.com", "t.co", "fb.watch",
        "bit.ly", "tinyurl.com", "pin.it", "redd.it", "on.soundcloud.com", "ok.me",
    )
    private val TIKTOK_ID = Regex("""/(video|photo|v)/(\d{8,})""")

    /** Las apps comparten a veces texto extra ("Mira este video… https://…"); nos quedamos con el primer enlace. */
    fun extract(text: String?): String? =
        text?.let { URL_IN_TEXT.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

    /**
     * Convierte enlaces cortos (vt.tiktok.com/…, tiktok.com/t/…, t.co, fb.watch…) en el enlace real
     * del video, siguiendo las redirecciones a mano y deteniéndose antes de cualquier tienda de apps.
     */
    fun resolve(url: String): String {
        var current = url
        if (needsResolving(current)) {
            repeat(8) {
                val next = nextHop(current) ?: return@repeat
                val host = Uri.parse(next).host?.lowercase()
                if (!next.startsWith("http") || host == null || host in STORES) return normalize(current)
                current = next
                if (!needsResolving(current)) return normalize(current)
            }
        }
        return normalize(current)
    }

    private fun needsResolving(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        if (host in SHORTENERS) return true
        val path = uri.path.orEmpty()
        return host.endsWith("tiktok.com") && !TIKTOK_ID.containsMatchIn(path)
    }

    /** Siguiente salto de la redirección; si la página responde 200, busca dentro el enlace real del video. */
    private fun nextHop(url: String): String? {
        for (ua in listOf(DESKTOP_UA, Http.UA)) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", ua)
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val raw = conn.getHeaderField("Location")
                        // TikTok a veces redirige a su app (snssdk1233://aweme/detail/<id>…); de ahí sale el id del video.
                        raw?.let { appLinkToWeb(it) }?.let { return it }
                        val location = raw?.let { runCatching { URL(URL(url), it).toString() }.getOrNull() }
                        if (location != null && Uri.parse(location).host?.lowercase() !in STORES) return location
                    } else if (code in 200..299) {
                        val html = conn.inputStream.bufferedReader().use { r ->
                            val buf = CharArray(300_000)
                            val n = r.read(buf)
                            if (n > 0) String(buf, 0, n) else ""
                        }
                        VIDEO_IN_HTML.find(html.replace("\\u002F", "/").replace("\\/", "/"))?.let { return it.value }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private val AWEME_ID = Regex("""aweme/detail/(\d{8,})""")

    /** Convierte un enlace de app de TikTok (snssdk…://aweme/detail/<id>) en el enlace web del video. */
    fun appLinkToWeb(link: String): String? {
        if (link.startsWith("http")) return null
        val id = AWEME_ID.find(link)?.groupValues?.get(1) ?: return null
        return "https://www.tiktok.com/@i/video/$id"
    }

    private val VIDEO_IN_HTML = Regex("""https://(?:www|m)\.tiktok\.com/@[^"'\s/?]+/(?:video|photo)/\d{8,}""")

    /** Deja los enlaces de TikTok en su forma canónica, que es la que entienden todas las APIs. */
    private fun normalize(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host?.lowercase()?.endsWith("tiktok.com") == true) {
            TIKTOK_ID.find(uri.path.orEmpty())?.let { m ->
                val user = Regex("""/@([^/]+)""").find(uri.path.orEmpty())?.groupValues?.get(1) ?: "i"
                val kind = if (m.groupValues[1] == "photo") "photo" else "video"
                return "https://www.tiktok.com/@$user/$kind/${m.groupValues[2]}"
            }
        }
        return url
    }

    fun isAppStore(url: String): Boolean = Uri.parse(url).host?.lowercase() in STORES
}
