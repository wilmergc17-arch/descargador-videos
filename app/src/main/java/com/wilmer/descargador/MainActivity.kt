package com.wilmer.descargador

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var versionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        askPermissions()

        val urlField = findViewById<EditText>(R.id.url)
        val audioOnly = findViewById<CheckBox>(R.id.audioOnly)
        versionText = findViewById(R.id.version)

        findViewById<Button>(R.id.paste).setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            urlField.setText(Links.extract(text) ?: text.orEmpty())
        }
        findViewById<Button>(R.id.download).setOnClickListener {
            val url = Links.extract(urlField.text.toString())
            if (url == null) {
                Toast.makeText(this, "Pega un enlace válido", Toast.LENGTH_SHORT).show()
            } else {
                DownloadService.start(this, url, audioOnly.isChecked)
                Toast.makeText(this, "Descargando… mira la notificación", Toast.LENGTH_SHORT).show()
                urlField.text.clear()
            }
        }

        findViewById<Button>(R.id.browser).setOnClickListener {
            val text = urlField.text.toString().trim()
            startActivity(BrowserActivity.intent(this, Links.extract(text) ?: text.ifBlank { "https://www.google.com" }))
        }

        findViewById<Button>(R.id.loginTiktok).setOnClickListener { LoginActivity.open(this, Cookies.SITES["tiktok"]!!) }
        findViewById<Button>(R.id.loginOkru).setOnClickListener { LoginActivity.open(this, Cookies.SITES["okru"]!!) }
        findViewById<Button>(R.id.loginInstagram).setOnClickListener { LoginActivity.open(this, Cookies.SITES["instagram"]!!) }
        findViewById<Button>(R.id.loginFacebook).setOnClickListener { LoginActivity.open(this, Cookies.SITES["facebook"]!!) }
        findViewById<Button>(R.id.loginX).setOnClickListener { LoginActivity.open(this, Cookies.SITES["x"]!!) }
        findViewById<Button>(R.id.logout).setOnClickListener {
            Cookies.clear(this)
            Toast.makeText(this, "Sesiones cerradas", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.report).setOnClickListener { startActivity(ReportActivity.intent(this, null)) }

        findViewById<Button>(R.id.update).setOnClickListener { button ->
            button.isEnabled = false
            versionText.text = "Actualizando…"
            thread {
                val result = try {
                    when (Engine.update(this)) {
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Ya tienes la última versión"
                        else -> "Actualizado"
                    }
                } catch (e: Exception) {
                    "No se pudo actualizar: ${e.message?.take(120)}"
                }
                runOnUiThread {
                    button.isEnabled = true
                    showVersion(result)
                }
            }
        }

        thread {
            try {
                Engine.ensureReady(this)
                runOnUiThread { showVersion(null) }
            } catch (e: Exception) {
                runOnUiThread { versionText.text = "Error al preparar el motor: ${e.message?.take(120)}" }
            }
        }
    }

    private fun showVersion(prefix: String?) {
        val v = "App ${BuildConfig.VERSION_NAME} · yt-dlp ${Engine.version(this)}"
        versionText.text = if (prefix != null) "$prefix\n$v" else v
    }

    private fun askPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }
}
