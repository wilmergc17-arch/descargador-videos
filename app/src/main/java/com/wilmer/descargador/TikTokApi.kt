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

    fun handles(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com")
    }

    fun download(url: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val response = JSONObject(Http.get("$BASE/api/?hd=1&url=" + URLEncoder.encode(url, "UTF-8")))
        if (response.optInt("code", -1) != 0) {
            throw IOException("TikTok: " + response.optString("msg", "respuesta inválida"))
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

    private fun absolute(u: String) = if (u.startsWith("http")) u else BASE + u
}
