package com.wilmer.descargador

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Navegador para páginas que ningún método reconoce: mientras la página carga y se reproduce
 * el video, detecta los archivos de video que pide (mp4, m3u8, webm…) y permite descargarlos.
 */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var found: Button
    private val media = LinkedHashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Buscar video en la página"

        address = EditText(this).apply {
            hint = "Dirección de la página"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    load(text.toString())
                    true
                } else false
            }
        }
        found = Button(this).apply {
            text = "Reproduce el video para detectarlo"
            setOnClickListener { showFound() }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(Bridge(), "Descargador")
            webViewClient = Client()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(address, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(found, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        load(intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com")
    }

    private fun load(input: String) {
        val text = input.trim()
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        address.setText(url)
        web.loadUrl(url)
    }

    private fun addMedia(url: String) {
        if (!isMedia(url)) return
        runOnUiThread {
            if (media.add(url)) found.text = "Videos encontrados: ${media.size} — toca para descargar"
        }
    }

    private fun showFound() {
        if (media.isEmpty()) {
            Toast.makeText(this, "Aún no encontré videos. Dale play al video en la página.", Toast.LENGTH_LONG).show()
            return
        }
        val items = media.toList().asReversed()
        val labels = items.mapIndexed { i, u ->
            val uri = Uri.parse(u)
            val kind = when {
                u.contains(".m3u8", true) -> "stream HLS"
                u.contains(".mpd", true) -> "stream DASH"
                else -> Http.guessExtension(u, null).uppercase()
            }
            "${i + 1}. $kind · ${uri.host}\n${uri.lastPathSegment?.take(50) ?: ""}"
        }
        AlertDialog.Builder(this)
            .setTitle("Elige el video (el más reciente primero)")
            .setItems(labels.toTypedArray()) { _, which ->
                DownloadService.start(this, items[which], audioOnly = false, referer = web.url ?: items[which])
                Toast.makeText(this, "Descargando… mira la notificación", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            addMedia(request.url.toString())
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Evita que anuncios abran otras apps o la Play Store.
            val scheme = request.url.scheme
            return scheme != "http" && scheme != "https"
        }

        override fun onPageFinished(view: WebView, url: String) {
            address.setText(url)
            view.evaluateJavascript(SCAN_JS, null)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun found(url: String) = addMedia(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"

        private val MEDIA = Regex(
            """\.(mp4|m4v|mov|webm|m3u8|mpd)(\?|#|$)|mime=video|/videoplayback\?""", RegexOption.IGNORE_CASE
        )

        fun isMedia(url: String): Boolean =
            url.startsWith("http") && MEDIA.containsMatchIn(url) && !url.contains(".m4s", true)

        // Revisa cada 2 segundos las etiquetas <video> de la página por si el video no pasa por la red de forma visible.
        private const val SCAN_JS = """
            (function() {
              if (window.__descargador) return; window.__descargador = true;
              setInterval(function() {
                document.querySelectorAll('video, video source').forEach(function(v) {
                  var s = v.currentSrc || v.src;
                  if (s && s.indexOf('http') === 0) Descargador.found(s);
                });
              }, 2000);
            })();
        """

        fun intent(context: Context, url: String) =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
