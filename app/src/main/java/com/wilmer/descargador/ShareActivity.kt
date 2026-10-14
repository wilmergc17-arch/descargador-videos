package com.wilmer.descargador

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Recibe el enlace desde el menú "Compartir", lanza la descarga y se cierra sin mostrar nada. */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val url = Links.extract(text)
        if (url == null) {
            Toast.makeText(this, "No encontré un enlace en lo que compartiste", Toast.LENGTH_LONG).show()
        } else {
            DownloadService.start(this, url, audioOnly = false)
            Toast.makeText(this, "Descargando…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
