package com.wilmer.descargador

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Peticiones HTTP simples para las APIs de respaldo y la descarga directa de archivos. */
object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, headers)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} en ${host(url)}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** POST con cuerpo JSON. Devuelve el cuerpo aunque el código sea de error, porque algunas APIs explican el fallo ahí. */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                ?: throw IOException("HTTP ${conn.responseCode} en ${host(url)}")
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun save(url: String, dest: File, onProgress: (Int) -> Unit, headers: Map<String, String> = emptyMap()) {
        val conn = open(url, headers)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} al bajar de ${host(url)}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPercent) {
                                lastPercent = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (dest.length() == 0L) throw IOException("Archivo vacío de ${host(url)}")
        } finally {
            conn.disconnect()
        }
    }

    /** Extensión a partir del tipo de contenido o de la URL, para nombrar archivos de APIs que no la dan. */
    fun guessExtension(url: String, type: String?): String = when {
        type == "photo" || type == "image" -> "jpg"
        type == "gif" -> "mp4"
        else -> Regex("""\.(mp4|webm|mov|m4a|mp3|jpg|jpeg|png|webp)(\?|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase() ?: "mp4"
    }

    fun safeName(text: String, fallback: String): String =
        text.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ").replace(Regex("""\s+"""), " ").trim()
            .take(70).ifBlank { fallback }

    private fun host(url: String) = runCatching { URL(url).host }.getOrDefault(url)
}
