package com.wilmer.descargador

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Copia lo descargado a la galería: videos en Movies, fotos en Pictures, audio en Music. */
object MediaSaver {
    private const val FOLDER = "Descargar Video"

    data class Saved(val uri: Uri?, val mime: String)

    fun save(context: Context, file: File): Saved {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "video/mp4"
        val kind = when {
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.VIDEO
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(context, file, mime, kind)
        } else {
            saveLegacy(context, file, mime, kind)
        }
        return Saved(uri, mime)
    }

    private enum class Kind(val dir: String) {
        VIDEO(Environment.DIRECTORY_MOVIES),
        IMAGE(Environment.DIRECTORY_PICTURES),
        AUDIO(Environment.DIRECTORY_MUSIC),
    }

    private fun saveMediaStore(context: Context, file: File, mime: String, kind: Kind): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = when (kind) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.dir}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("No se pudo crear el archivo en la galería")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, mime: String, kind: Kind): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(kind.dir), FOLDER).apply { mkdirs() }
        var dest = File(dir, file.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        file.copyTo(dest)
        var result: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
            result = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }
}
