package com.wilmer.descargador

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Prepara yt-dlp (Python + ffmpeg incluidos en la app) y lo mantiene actualizado. */
object Engine {
    private const val PREFS = "motor"
    private const val KEY_LAST_UPDATE = "ultima_actualizacion"
    private const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var ready = false

    @Synchronized
    fun ensureReady(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }

    /** Las redes cambian seguido; actualiza yt-dlp como mucho una vez al día. Si falla, sigue con la versión actual. */
    fun updateIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0) < UPDATE_EVERY_MS) return
        try {
            update(context)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun update(context: Context): YoutubeDL.UpdateStatus? {
        ensureReady(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        return status
    }

    fun version(context: Context): String =
        YoutubeDL.getInstance().versionName(context.applicationContext) ?: "incluida en la app"
}
