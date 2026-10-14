package com.wilmer.descargador

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Muestra el informe completo de la última descarga fallida, para copiarlo o compartirlo. */
class ReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Informe de la descarga"
        val report = file(this).takeIf { it.exists() }?.readText() ?: "Todavía no hay ningún informe."
        val url = intent.getStringExtra(EXTRA_URL)

        val text = TextView(this).apply {
            this.text = report
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(32, 32, 32, 32)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                this.text = "Copiar"
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Informe", report))
                    Toast.makeText(context, "Informe copiado", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                this.text = "Compartir"
                setOnClickListener {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                        "Compartir informe"
                    ))
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (url != null) addView(Button(context).apply {
                this.text = "Navegador"
                setOnClickListener { startActivity(BrowserActivity.intent(context, url)) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(text) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun file(context: Context) = File(context.filesDir, "ultimo_informe.txt")

        fun intent(context: Context, url: String?) =
            Intent(context, ReportActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
