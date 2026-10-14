package com.wilmer.descargador

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/** Abre la web de la red para iniciar sesión; al salir guarda las cookies para yt-dlp. */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Inicia sesión y pulsa Listo"
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val start = intent.getStringExtra(EXTRA_URL) ?: "https://www.instagram.com"
        // TikTok en móvil intenta abrir su app; como navegador de escritorio deja iniciar sesión en la web.
        if (start.contains("tiktok")) web.settings.userAgentString = Http.DESKTOP_UA
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return !url.startsWith("http") || Links.isAppStore(url)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        setContentView(web)
        web.loadUrl(start)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Listo").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        Cookies.export(this)
    }

    companion object {
        private const val EXTRA_URL = "url"
        fun open(context: Context, url: String) =
            context.startActivity(Intent(context, LoginActivity::class.java).putExtra(EXTRA_URL, url))
    }
}
