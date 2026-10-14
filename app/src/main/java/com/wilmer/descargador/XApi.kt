package com.wilmer.descargador

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** X/Twitter sin cuenta a través de la API pública de vxtwitter (y fxtwitter como respaldo). */
object XApi {
    private val HOSTS = setOf("x.com", "twitter.com", "mobile.twitter.com", "mobile.x.com", "www.x.com", "www.twitter.com")
    private val STATUS = Regex("""/([^/]+)/status(?:es)?/(\d+)""")

    fun handles(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.host?.lowercase() in HOSTS && STATUS.containsMatchIn(uri.path.orEmpty())
    }

    fun download(url: String, dir: File, onProgress: (Int) -> Unit): List<File> {
        val match = STATUS.find(Uri.parse(url).path.orEmpty()) ?: throw IOException("X: enlace sin publicación")
        val (user, id) = match.destructured
        val media = try {
            fromVx(user, id)
        } catch (e: Exception) {
            fromFx(user, id)
        }
        if (media.isEmpty()) throw IOException("X: la publicación no tiene videos ni fotos")
        return media.mapIndexed { i, (mediaUrl, type) ->
            val suffix = if (media.size > 1) " ${i + 1}" else ""
            File(dir, "X $user [$id]$suffix.${Http.guessExtension(mediaUrl, type)}").also {
                Http.save(mediaUrl, it, { p -> onProgress((i * 100 + p) / media.size) })
            }
        }
    }

    private fun fromVx(user: String, id: String): List<Pair<String, String>> {
        val json = JSONObject(Http.get("https://api.vxtwitter.com/$user/status/$id"))
        val list = json.optJSONArray("media_extended") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .map { it.getString("url") to it.optString("type", "video") }
    }

    private fun fromFx(user: String, id: String): List<Pair<String, String>> {
        val tweet = JSONObject(Http.get("https://api.fxtwitter.com/$user/status/$id")).getJSONObject("tweet")
        val all = tweet.optJSONObject("media")?.optJSONArray("all") ?: return emptyList()
        return (0 until all.length()).map { all.getJSONObject(it) }
            .map { it.getString("url") to it.optString("type", "video") }
    }
}
