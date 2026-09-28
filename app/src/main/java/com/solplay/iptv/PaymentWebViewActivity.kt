package com.solplay.iptv

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

/**
 * Ouvre la page de paiement en ligne dans un WebView intégré à l'app.
 *
 * Ne fait AUCUNE hypothèse sur "quand le paiement est terminé" - pas de
 * détection d'URL de retour pour débloquer quoi que ce soit côté app
 * (ce serait non sécurisé, voir OnlinePaymentClient.kt). L'utilisateur
 * ferme cet écran une fois le paiement fait (bouton retour), et retombe
 * sur LicenseActivity, qui détecte l'activation réelle automatiquement dès
 * que le webhook de paiement (ou check-payment) l'aura confirmée côté Firebase (LiveLicenseWatcher/
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
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = handleUrl(request?.url?.toString())

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleUrl(url)

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

    /**
     * Gère les liens que le WebView ne sait pas charger (wave://, intent://, etc.).
     * Retourne true si le lien a été pris en charge ici, false pour laisser
     * le WebView charger normalement (http/https).
     */
    private fun handleUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false

        // 1) Essayer d'ouvrir l'app Wave avec le lien tel quel.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return true
        } catch (_: ActivityNotFoundException) {
            // Wave n'est pas installée -> on passe au plan B
        } catch (_: Exception) {
        }

        // 2) Plan B : wave://capture/https://pay.wave.com/... -> ouvrir la partie https
        //    dans le navigateur (pas dans le WebView, pour éviter de retomber sur wave://).
        val prefix = "wave://capture/"
        if (lower.startsWith(prefix)) {
            val webUrl = url.substring(prefix.length)
            if (webUrl.startsWith("https://", ignoreCase = true)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                    return true
                } catch (_: Exception) {
                }
            }
        }

        Toast.makeText(this, "Impossible d'ouvrir l'application de paiement", Toast.LENGTH_LONG).show()
        return true // on consomme le lien pour ne plus afficher la page d'erreur
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
