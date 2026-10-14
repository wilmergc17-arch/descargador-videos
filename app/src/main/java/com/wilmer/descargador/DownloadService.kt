package com.wilmer.descargador

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Descarga los enlaces en cola, uno a la vez, mostrando el progreso en una notificación. */
class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    @Volatile
    private var currentId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            return START_NOT_STICKY
        }
        // Debe llamarse enseguida tras startForegroundService, aunque el intent venga vacío.
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS, progressNotification("Preparando…", -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (pending.get() == 0) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO, false)
        pending.incrementAndGet()
        executor.execute {
            try {
                download(url, audioOnly)
            } finally {
                if (pending.decrementAndGet() == 0) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun download(url: String, audioOnly: Boolean) {
        val jobId = UUID.randomUUID().toString()
        val dir = File(cacheDir, "descargas/$jobId").apply { mkdirs() }
        try {
            showProgress("Preparando…", -1)
            Engine.ensureReady(this)
            Engine.updateIfStale(this)

            val request = YoutubeDLRequest(url).apply {
                addOption("-o", "${dir.absolutePath}/%(title).70B [%(id)s].%(ext)s")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--windows-filenames")
                if (audioOnly) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", VIDEO_FORMAT)
                    addOption("--merge-output-format", "mp4")
                }
                Cookies.fileIfAny(this@DownloadService)?.let { addOption("--cookies", it.absolutePath) }
            }

            currentId = jobId
            var lastUpdate = 0L
            YoutubeDL.getInstance().execute(request, jobId) { progress, _, _ ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 700) {
                    lastUpdate = now
                    showProgress("Descargando…", progress.toInt().coerceIn(0, 100))
                }
            }
            currentId = null

            showProgress("Guardando en la galería…", -1)
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (files.isEmpty()) throw IllegalStateException("No se encontró ningún archivo descargado")
            val saved = files.map { MediaSaver.save(this, it) }
            val title = files.first().nameWithoutExtension.substringBeforeLast(" [")
            notifyDone(title, saved.first(), files.size)
        } catch (_: YoutubeDL.CanceledException) {
            notifyResult("Descarga cancelada", url, null)
        } catch (e: Exception) {
            notifyResult("No se pudo descargar", friendlyError(e), null)
        } finally {
            currentId = null
            dir.deleteRecursively()
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        val line = msg.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: msg
        val hint = when {
            listOf("login", "cookies", "sign in", "private", "authentication").any { line.contains(it, true) } ->
                "La red bloqueó la descarga sin cuenta."
            line.contains("Unsupported URL", true) -> "Ese enlace no tiene un video que se pueda descargar."
            line.contains("Unable to resolve host", true) -> "Sin conexión a internet."
            else -> null
        }
        // El detalle original de yt-dlp ayuda a diagnosticar qué red falla y por qué.
        val detail = line.take(400).ifBlank { "Error desconocido" }
        return if (hint != null) "$hint\n\nDetalle: $detail" else detail
    }

    private fun progressNotification(text: String, percent: Int) =
        NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(
                pending.get().let { if (it > 1) "Descargar Video (${it - 1} en cola)" else "Descargar Video" }
            )
            .setContentText(if (percent >= 0) "$text $percent%" else text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0, "Cancelar",
                PendingIntent.getService(
                    this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    @SuppressLint("MissingPermission")
    private fun showProgress(text: String, percent: Int) {
        val nm = NotificationManagerCompat.from(this)
        if (nm.areNotificationsEnabled()) nm.notify(NOTIF_PROGRESS, progressNotification(text, percent))
    }

    private fun notifyDone(title: String, saved: MediaSaver.Saved, count: Int) {
        val open = saved.uri?.let { uri ->
            PendingIntent.getActivity(
                this, uri.hashCode(),
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, saved.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = if (count > 1) "$count archivos guardados en la galería" else "Guardado en la galería"
        notifyResult(text, title, open)
    }

    @SuppressLint("MissingPermission")
    private fun notifyResult(title: String, text: String, open: PendingIntent?) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(this, App.CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val NOTIF_PROGRESS = 1
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO = "audio"
        private const val ACTION_CANCEL = "cancelar"

        // H.264 + AAC primero para que cualquier galería lo reproduzca; si no existe, lo mejor disponible.
        private const val VIDEO_FORMAT =
            "bv*[vcodec^=avc]+ba[ext=m4a]/bv*[vcodec^=avc]+ba/b[ext=mp4]/bv*+ba/b"

        fun start(context: Context, url: String, audioOnly: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_AUDIO, audioOnly)
            )
        }
    }
}
