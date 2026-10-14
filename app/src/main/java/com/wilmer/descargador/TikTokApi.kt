package com.wilmer.descargador

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * TikTok sin cuenta ni marca de agua a través de la API pública de tikwm.com,
 * el mismo tipo de servicio que usan los atajos de iOS.
 */
object TikTokApi {
    private const val BASE = "https://www.tikwm.com"

    fun handles(url: String): Boolean =
        Links.isTikTokHost(Uri.parse(url).host) || url.contains("tiktok.com/", true) || url.contains("tt.site/", true)

    /** yt-dlp a veces reconoce el video aunque no pueda bajarlo ("[TikTok] 7692…"); con ese id armamos el enlace web. */
    fun urlFromYtDlpError(message: String?): String? =
        message?.let { Regex("""\[TikTok\] (\d{8,})""").find(it) }?.groupValues?.get(1)
            ?.let { "https://www.tiktok.com/@i/video/$it" }

    /** Prueba tikwm (GET y POST) y luego tiklydown; devuelve el primer resultado que funcione. */
    fun download(url: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val errors = mutableListOf<String>()
        val encoded = URLEncoder.encode(url, "UTF-8")
        val sources = listOf<Pair<String, () -> List<File>>>(
            "tikwm" to { fromTikwm(Http.get("$BASE/api/?hd=1&url=$encoded"), dir, onProgress) },
            "tikwm2" to { fromTikwm(Http.postForm("$BASE/api/", "url=$encoded&hd=1"), dir, onProgress) },
            "tiklydown" to { fromTiklydown(url, dir, onProgress) },
        )
        for ((name, source) in sources) {
            try {
                return source()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                errors += "$name: ${e.message?.take(60)}"
                dir.listFiles()?.forEach { it.delete() }
            }
        }
        throw IOException(errors.joinToString(" | "))
    }

    private fun fromTikwm(body: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val response = JSONObject(body)
        if (response.optInt("code", -1) != 0) {
            throw IOException(response.optString("msg", "respuesta inválida"))
        }
        val data = response.getJSONObject("data")
        val id = data.optString("id", "tiktok")
        val title = Http.safeName(data.optString("title"), "TikTok")

        val images = data.optJSONArray("images")
        if (images != null && images.length() > 0) {
            // Publicación de fotos (carrusel).
            return (0 until images.length()).map { i ->
                File(dir, "$title [$id] ${i + 1}.jpg").also {
                    Http.save(absolute(images.getString(i)), it, { p -> onProgress((i * 100 + p) / images.length()) })
                }
            }
        }
        val video = data.optString("hdplay").ifBlank { data.optString("play") }
        if (video.isBlank()) throw IOException("TikTok: no se encontró el video")
        val file = File(dir, "$title [$id].mp4")
        Http.save(absolute(video), file, onProgress)
        return listOf(file)
    }

    private fun fromTiklydown(url: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val json = JSONObject(Http.get("https://api.tiklydown.eu.org/api/download?url=" + URLEncoder.encode(url, "UTF-8")))
        val id = json.optString("id", "tiktok")
        val title = Http.safeName(json.optString("title"), "TikTok")
        val images = json.optJSONArray("images")
        if (images != null && images.length() > 0) {
            return (0 until images.length()).map { i ->
                val item = images.get(i)
                val imageUrl = if (item is JSONObject) item.optString("url") else item.toString()
                File(dir, "$title [$id] ${i + 1}.jpg").also {
                    Http.save(imageUrl, it, { p -> onProgress((i * 100 + p) / images.length()) })
                }
            }
        }
        val video = json.optJSONObject("video")
        val videoUrl = video?.optString("noWatermark").orEmpty().ifBlank { video?.optString("watermark").orEmpty() }
        if (videoUrl.isBlank()) throw IOException(json.optString("message", "sin video"))
        val file = File(dir, "$title [$id].mp4")
        Http.save(videoUrl, file, onProgress)
        return listOf(file)
    }

    private fun absolute(u: String) = if (u.startsWith("http")) u else BASE + u
}
