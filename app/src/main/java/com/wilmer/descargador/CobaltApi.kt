package com.wilmer.descargador

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Respaldo para cualquier red (Instagram, Facebook, YouTube, Reddit, Pinterest, Vimeo, etc.)
 * mediante servidores públicos de cobalt que no piden cuenta. La lista de servidores se toma de
 * instances.cobalt.best y se guarda un día en caché; se prueban por orden de puntuación.
 */
object CobaltApi {
    private val LIST_URLS = listOf(
        "https://instances.cobalt.best/api/instances.json",
        "https://cobalt.directory/api/working?type=api",
    )
    private const val CACHE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_TRIES = 6

    fun download(context: Context, url: String, audioOnly: Boolean, dir: File, onProgress: (Int) -> Unit): List<File> {
        val instances = instances(context)
        var lastError: Exception? = null
        for (api in instances.take(MAX_TRIES)) {
            try {
                return downloadFrom(api, url, audioOnly, dir, onProgress)
            } catch (e: Exception) {
                lastError = e
                dir.listFiles()?.forEach { it.delete() }
            }
        }
        throw IOException("Servidores de respaldo: ${lastError?.message}")
    }

    private fun downloadFrom(api: String, url: String, audioOnly: Boolean, dir: File, onProgress: (Int) -> Unit): List<File> {
        val body = JSONObject()
            .put("url", url)
            .put("videoQuality", "1080")
            .put("youtubeVideoCodec", "h264")
            .put("filenameStyle", "basic")
            .put("downloadMode", if (audioOnly) "audio" else "auto")
            .put("audioFormat", "mp3")
        val res = JSONObject(Http.postJson(api, body.toString()))
        return when (res.optString("status")) {
            "tunnel", "redirect" -> {
                val fileUrl = res.getString("url")
                val name = res.optString("filename").ifBlank { "video.${if (audioOnly) "mp3" else "mp4"}" }
                val file = File(dir, Http.safeName(name.substringBeforeLast('.'), "video") + "." +
                        name.substringAfterLast('.', if (audioOnly) "mp3" else "mp4"))
                Http.save(fileUrl, file, onProgress)
                listOf(file)
            }
            "picker" -> {
                val items = res.getJSONArray("picker")
                (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    val itemUrl = item.getString("url")
                    File(dir, "media ${System.currentTimeMillis()} ${i + 1}.${Http.guessExtension(itemUrl, item.optString("type"))}")
                        .also { Http.save(itemUrl, it, { p -> onProgress((i * 100 + p) / items.length()) }) }
                }
            }
            "error" -> throw IOException(res.optJSONObject("error")?.optString("code") ?: "error")
            else -> throw IOException("respuesta desconocida")
        }
    }

    /** URLs de API de los servidores en línea, mejor puntuados primero. Prueba dos directorios públicos. */
    private fun instances(context: Context): List<String> {
        val cache = File(context.cacheDir, "cobalt-instancias.txt")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) {
            cache.readLines().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val errors = mutableListOf<String>()
        for (source in LIST_URLS) {
            try {
                val text = Http.get(source, mapOf("User-Agent" to Http.DESKTOP_UA, "Accept" to "application/json"))
                val list = parse(text)
                if (list.isNotEmpty()) {
                    cache.writeText(list.joinToString("\n"))
                    return list
                }
                errors += "${Uri.parse(source).host}: vacía"
            } catch (e: Exception) {
                errors += "${Uri.parse(source).host}: ${e.message?.take(100)}"
            }
        }
        throw IOException("lista de servidores: " + errors.joinToString(" | "))
    }

    private fun parse(text: String): List<String> {
        val trimmed = text.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).let { o ->
                o.optJSONArray("instances") ?: o.optJSONArray("data") ?: o.optJSONArray("results") ?: JSONArray()
            }
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { isOnline(it) && !needsAuth(it) }
            .sortedByDescending { it.optDouble("score", 0.0) }
            .mapNotNull { apiUrl(it) }
            .distinct()
    }

    private fun isOnline(o: JSONObject): Boolean = when (val online = o.opt("online")) {
        is JSONObject -> online.optBoolean("api", true)
        is Boolean -> online
        else -> true
    }

    private fun needsAuth(o: JSONObject): Boolean =
        o.optBoolean("turnstile", false) || o.optBoolean("auth", false) || o.optBoolean("apiKey", false)

    private fun apiUrl(o: JSONObject): String? {
        val api = listOf("api", "api_url", "url", "host").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
            ?: return null
        if (api.startsWith("http")) return api.trimEnd('/') + "/"
        val protocol = o.optString("protocol").ifBlank { "https" }
        return "$protocol://${api.trimEnd('/')}/"
    }
}
