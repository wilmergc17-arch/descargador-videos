package com.wilmer.descargador

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Guarda las cookies de las sesiones abiertas en LoginActivity en formato Netscape,
 * que es el que yt-dlp acepta con --cookies.
 */
object Cookies {
    val SITES = mapOf(
        "instagram" to "https://www.instagram.com",
        "facebook" to "https://www.facebook.com",
        "x" to "https://x.com",
        "tiktok" to "https://www.tiktok.com/login",
    )

    private fun file(context: Context) = File(context.filesDir, "cookies.txt")

    fun fileIfAny(context: Context): File? = file(context).takeIf { it.length() > 0 }

    fun export(context: Context) {
        val cm = CookieManager.getInstance()
        cm.flush()
        val farFuture = System.currentTimeMillis() / 1000 + 365L * 24 * 60 * 60
        val lines = StringBuilder("# Netscape HTTP Cookie File\n")
        for (url in SITES.values) {
            val raw = cm.getCookie(url) ?: continue
            val domain = "." + url.substringAfter("://").substringBefore("/").removePrefix("www.")
            for (pair in raw.split(";")) {
                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (name.isEmpty()) continue
                lines.append("$domain\tTRUE\t/\tTRUE\t$farFuture\t$name\t$value\n")
            }
        }
        file(context).writeText(lines.toString())
    }

    fun clear(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        file(context).delete()
    }
}
