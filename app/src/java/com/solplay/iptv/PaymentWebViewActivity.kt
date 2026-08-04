package com.solplay.iptv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

/**
 * Ouvre la page de paiement Djèko (Jeko) dans un WebView intégré à l'app.
 *
 * Ne fait AUCUNE hypothèse sur "quand le paiement est terminé" - pas de
 * détection d'URL de retour pour débloquer quoi que ce soit côté app
 * (ce serait non sécurisé, voir DjekoPaymentClient.kt). L'utilisateur
 * ferme cet écran une fois le paiement fait (bouton retour), et retombe
 * sur LicenseActivity, qui détecte l'activation réelle automatiquement dès
 * que le webhook Djèko l'aura confirmée côté Firebase (LiveLicenseWatcher/
 * sondage 10s déjà en place).
 */
class PaymentWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYMENT_URL = "extra_payment_url"
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_PAYMENT_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val progress = ProgressBar(this)
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = android.view.View.GONE
                }
            }
            loadUrl(url)
        }
        webView = wv

        val root = FrameLayout(this).apply {
            addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
        }
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
