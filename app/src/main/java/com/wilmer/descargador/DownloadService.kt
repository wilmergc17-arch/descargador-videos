package com.wilmer.descargador

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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

/**
 * Descarga los enlaces en cola, uno a la vez, mostrando el progreso en una notificación.
 * Para cada enlace prueba varios métodos en orden hasta que uno funcione:
 * API sin cuenta propia de la red (TikTok, X) → yt-dlp (más de 1.800 sitios, incluido Ok.ru)
 * → servidores públicos de cobalt → descarga directa (para enlaces sacados del navegador).
 */
class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    @Volatile
    private var currentId: String? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            currentId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            worker?.interrupt()
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
        val referer = intent.getStringExtra(EXTRA_REFERER)
        pending.incrementAndGet()
        executor.execute {
            worker = Thread.currentThread()
            cancelled = false
            try {
                download(url, audioOnly, referer)
            } finally {
                worker = null
                Thread.interrupted()
                if (pending.decrementAndGet() == 0) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun download(sharedUrl: String, audioOnly: Boolean, referer: String?) {
        if (referer == null) showProgress("Abriendo enlace…", -1)
        val url = if (referer == null) Links.resolve(sharedUrl) else sharedUrl
        val jobId = UUID.randomUUID().toString()
        val dir = File(cacheDir, "descargas/$jobId").apply { mkdirs() }
        var lastUpdate = 0L
        val onProgress = { percent: Int ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 700) {
                lastUpdate = now
                showProgress("Descargando…", percent.coerceIn(0, 100))
            }
        }

        val methods = buildList<Pair<String, () -> Unit>> {
            if (referer == null && (TikTokApi.handles(url) || TikTokApi.handles(sharedUrl))) add("TikTok" to { TikTokApi.download(if (TikTokApi.handles(url)) url else sharedUrl, dir, onProgress); Unit })
            if (referer == null && !audioOnly && XApi.handles(url)) add("X" to { XApi.download(url, dir, onProgress); Unit })
            add("yt-dlp" to { runYtDlp(url, audioOnly, referer, dir, jobId, onProgress); Unit })
            if (referer == null) {
                add("respaldo" to { CobaltApi.download(this@DownloadService, url, audioOnly, dir, onProgress); Unit })
            } else {
                add("directa" to {
                    val name = Http.safeName(Uri.parse(url).lastPathSegment?.substringBeforeLast('.') ?: "", "video")
                    Http.save(url, File(dir, "$name.${Http.guessExtension(url, null)}"), onProgress, mapOf("Referer" to referer))
                    Unit
                })
            }
        }

        val log = StringBuilder()
        log.append("Descargar Video ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}\n")
        log.append("Compartido: $sharedUrl\n")
        log.append("Enlace usado: $url\n")
        log.append("Métodos: ${methods.joinToString { it.first }}\n\n")

        try {
            val errors = mutableListOf<Pair<String, Exception>>()
            for ((name, method) in methods) {
                if (cancelled) throw InterruptedException()
                try {
                    log.append("▶ $name\n")
                    showProgress(if (errors.isEmpty()) "Descargando…" else "Probando otro método…", -1)
                    method()
                    if (downloadedFiles(dir).isNotEmpty()) {
                        log.append("✓ $name\n")
                        break
                    }
                    throw java.io.IOException("terminó sin archivos")
                } catch (e: YoutubeDL.CanceledException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) throw InterruptedException()
                    log.append("✗ $name: ${e.javaClass.simpleName}: ${e.message?.take(1500)}\n\n")
                    errors += name to e
                    dir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }

            val files = downloadedFiles(dir)
            if (files.isEmpty()) {
                // El error de yt-dlp es el más informativo para el resumen; el informe completo va aparte.
                val main = errors.firstOrNull { it.first == "yt-dlp" }?.second ?: errors.firstOrNull()?.second
                val message = main?.let { friendlyError(it) } ?: "No se encontró ningún archivo"
                ReportActivity.file(this).writeText(log.toString())
                notifyFailure(url, referer, message)
                return
            }
            showProgress("Guardando en la galería…", -1)
            val saved = files.map { MediaSaver.save(this, it) }
            val title = files.first().nameWithoutExtension.substringBeforeLast(" [")
            notifyDone(title, saved.first(), files.size)
        } catch (_: YoutubeDL.CanceledException) {
            notifyResult("Descarga cancelada", url, null)
        } catch (_: InterruptedException) {
            notifyResult("Descarga cancelada", url, null)
        } catch (e: Exception) {
            log.append("✗ error general: ${e.javaClass.simpleName}: ${e.message?.take(1500)}\n")
            ReportActivity.file(this).writeText(log.toString())
            notifyFailure(url, referer, friendlyError(e))
        } finally {
            currentId = null
            dir.deleteRecursively()
        }
    }

    private fun downloadedFiles(dir: File) = dir.listFiles()
        ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun runYtDlp(
        url: String, audioOnly: Boolean, referer: String?, dir: File, jobId: String, onProgress: (Int) -> Unit
    ) {
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
            if (referer != null) {
                addOption("--referer", referer)
                addOption("--add-header", "Origin:" + Uri.parse(referer).let { "${it.scheme}://${it.host}" })
            }
            Cookies.fileIfAny(this@DownloadService)?.let { addOption("--cookies", it.absolutePath) }
        }

        currentId = jobId
        try {
            YoutubeDL.getInstance().execute(request, jobId) { progress, _, _ -> onProgress(progress.toInt()) }
        } finally {
            currentId = null
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        val line = msg.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: msg
        val hint = when {
            listOf("login", "cookies", "sign in", "private", "authentication").any { line.contains(it, true) } ->
                "La red bloqueó la descarga sin cuenta (en TikTok suele ser un video marcado para adultos)."
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

    /** Si nada funcionó, ofrece abrir la página en el navegador de la app para buscar el video a mano. */
    /** Al tocar la notificación se abre el informe completo, con botones para copiarlo o buscar en el navegador. */
    private fun notifyFailure(url: String, referer: String?, message: String) {
        val open = PendingIntent.getActivity(
            this, url.hashCode(),
            ReportActivity.intent(this, if (referer == null) url else null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notifyResult("No se pudo descargar", "${message.take(300)}\n\nToca para ver el informe completo.", open)
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
        private const val EXTRA_REFERER = "referer"
        private const val ACTION_CANCEL = "cancelar"

        // H.264 + AAC primero para que cualquier galería lo reproduzca; si no existe, lo mejor disponible.
        private const val VIDEO_FORMAT =
            "bv*[vcodec^=avc]+ba[ext=m4a]/bv*[vcodec^=avc]+ba/b[ext=mp4]/bv*+ba/b"

        /** [referer] se usa para enlaces de video sacados de una página en el navegador de la app. */
        fun start(context: Context, url: String, audioOnly: Boolean, referer: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_AUDIO, audioOnly)
                    .putExtra(EXTRA_REFERER, referer)
            )
        }
    }
}
