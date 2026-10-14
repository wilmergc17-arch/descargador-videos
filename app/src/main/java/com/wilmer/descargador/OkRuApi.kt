package com.wilmer.descargador

import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Ok.ru sin yt-dlp: abre la página del reproductor (embed, móvil y normal), busca los enlaces
 * directos del CDN (okcdn.ru / mycdn.me) y baja el de mejor calidad. Si solo hay HLS (m3u8),
 * lo devuelve para que yt-dlp lo baje con ffmpeg.
 */
object OkRuApi {
    private val ID = Regex("""/(?:video|videoembed|live)/(\d+)""")
    private val CDN_URL = Regex("""https://[a-z0-9.-]+\.(?:okcdn\.ru|mycdn\.me)/[^"'\s<>\\]+""")

    fun handles(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        return (host == "ok.ru" || host == "odnoklassniki.ru") && ID.containsMatchIn(url)
    }

    /** Devuelve los archivos bajados, o lanza [HlsOnly] con el enlace m3u8 si no hay mp4 directo. */
    fun download(url: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val id = ID.find(url)?.groupValues?.get(1) ?: throw IOException("Ok.ru: enlace sin id de video")
        val pages = listOf(
            "https://ok.ru/videoembed/$id" to Http.DESKTOP_UA,
            "https://m.ok.ru/video/$id" to Http.UA,
            "https://ok.ru/video/$id" to Http.DESKTOP_UA,
        )
        val errors = mutableListOf<String>()
        var hls: String? = null
        for ((page, ua) in pages) {
            try {
                val html = clean(Http.get(page, mapOf("User-Agent" to ua, "Accept-Language" to "es,en;q=0.8")))
                val urls = CDN_URL.findAll(html).map { it.value.trimEnd(',', ';') }.distinct().toList()
                val direct = urls.filter { !it.contains(".m3u8") && !it.contains(".mpd") && it.contains("type=") }
                hls = hls ?: urls.firstOrNull { it.contains(".m3u8") }
                val best = direct.maxByOrNull { quality(it) }
                if (best != null) {
                    val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)
                    val file = File(dir, "${Http.safeName(title ?: "", "Ok.ru")} [$id].mp4")
                    Http.save(best, file, onProgress, mapOf("Referer" to "https://ok.ru/", "User-Agent" to ua))
                    return listOf(file)
                }
                errors += "${Uri.parse(page).host}: sin enlaces (${urls.size} del CDN)"
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                errors += "${Uri.parse(page).host}: ${e.message?.take(80)}"
            }
        }
        hls?.let { throw HlsOnly(it) }
        throw IOException("Ok.ru: " + errors.joinToString(" | "))
    }

    class HlsOnly(val url: String) : IOException("solo HLS")

    /** Ok.ru guarda los datos del reproductor como JSON escapado dentro de atributos HTML. */
    private fun clean(html: String) = html
        .replace("&quot;", "\"").replace("&amp;", "&")
        .replace("\\\\u0026", "&").replace("\\u0026", "&")
        .replace("\\\\/", "/").replace("\\/", "/")
        .replace("\\\"", "\"").replace("\\\\", "\\")

    /** En el CDN de Ok.ru, type=0 es la peor calidad y números mayores son mejores. */
    private fun quality(url: String) =
        Regex("""[?&]type=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}
