package com.solplay.iptv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityLicenseBinding
import kotlinx.coroutines.launch

class LicenseActivity : AppCompatActivity() {

    companion object {
        /** Message optionnel affiché à l'ouverture (ex: raison d'une éjection forcée par LiveLicenseWatcher). */
        const val EXTRA_REASON = "extra_reason"
    }

    private lateinit var binding: ActivityLicenseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DisclaimerDialog.showIfNeeded(this)

        intent.getStringExtra(EXTRA_REASON)?.let { reason ->
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        }

        val deviceKey = DeviceKeyManager.getDeviceKey(this)
        binding.tvDeviceKey.text = getString(R.string.device_key_format, deviceKey)

        // CORRECTIF (QR code visible dès l'ouverture) : la clé appareil n'était
        // affichée en QR que dans AboutActivity, inaccessible avant connexion.
        // On génère maintenant le QR directement sur cet écran d'activation,
        // pour que l'admin puisse le scanner sans que l'utilisateur se connecte.
        QrCodeGenerator.generateForDeviceKey(deviceKey).let { qr ->
            if (qr != null) {
                binding.ivDeviceKeyQr.setImageBitmap(qr)
                binding.ivDeviceKeyQr.visibility = android.view.View.VISIBLE
            }
        }

        refreshUiState()

        // Revérifie automatiquement auprès de Firebase à l'ouverture de l'écran,
        // puis en continu toutes les 10 secondes tant que cet écran est affiché
        // (en plus du bouton "Vérifier mon activation"), pour détecter
        // automatiquement l'activation faite par l'admin sans que
        // l'utilisateur ait besoin d'appuyer sur un bouton ou de relancer
        // l'application : dès que l'admin active la clé, l'écran bascule
        // seul vers l'interface normale.
        lifecycleScope.launch {
            while (true) {
                val active = TrialManager.checkOnlineLicense(this@LicenseActivity)
                refreshUiState()
                if (active) {
                    goToApp()
                    break
                }
                kotlinx.coroutines.delay(10_000)
            }
        }

        // Se met à jour chaque minute tant que l'écran est affiché, au lieu de
        // rester figé sur la valeur calculée à l'ouverture de l'écran.
        LiveCountdown.attach(this) { refreshUiState() }

        binding.btnContinueTrial.setOnClickListener {
            goToApp()
        }

        binding.btnCopyDeviceKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clé appareil SolPlay", deviceKey))
            Toast.makeText(this, "Clé copiée !", Toast.LENGTH_SHORT).show()
        }

        binding.btnVerifyActivation.setOnClickListener {
            binding.progressBarLicense.visibility = android.view.View.VISIBLE
            lifecycleScope.launch {
                val active = TrialManager.checkOnlineLicense(this@LicenseActivity)
                binding.progressBarLicense.visibility = android.view.View.GONE
                refreshUiState()
                if (active) {
                    Toast.makeText(this@LicenseActivity, R.string.license_success, Toast.LENGTH_LONG).show()
                    goToApp()
                } else {
                    Toast.makeText(this@LicenseActivity, "Pas encore activée. Contactez votre revendeur avec votre clé appareil.", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnWhatsApp.setOnClickListener {
            openWhatsAppContact(deviceKey)
        }

        binding.btnPayOnline.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
    }

    /**
     * Met à jour l'affichage selon l'état actuel :
     * - Licence Pro active -> date/heure d'expiration + temps restant
     * - Essai gratuit actif -> temps restant (heures/minutes)
     * - Ni l'un ni l'autre -> écran bloqué avec message + bouton WhatsApp
     */
    private fun refreshUiState() {
        val licensed = TrialManager.isLicensed(this)
        val trialActive = TrialManager.isTrialActive(this)

        when {
            licensed -> {
                val expiresAt = TrialManager.getLicenseExpiresAt(this)
                binding.tvStatus.text = if (expiresAt == 0L) {
                    getString(R.string.license_active_unlimited)
                } else {
                    val remaining = TrialManager.getRemainingLicenseMillis(this)
                    getString(
                        R.string.license_active_format,
                        TrialManager.formatDate(expiresAt),
                        TrialManager.formatDuration(remaining)
                    )
                }
                binding.btnContinueTrial.visibility = android.view.View.VISIBLE
                binding.groupBlocked.visibility = android.view.View.GONE
            }
            trialActive -> {
                val remaining = TrialManager.getRemainingTrialMillis(this)
                binding.tvStatus.text = getString(
                    R.string.trial_active_format,
                    TrialManager.formatDuration(remaining)
                )
                binding.btnContinueTrial.visibility = android.view.View.VISIBLE
                binding.groupBlocked.visibility = android.view.View.GONE
            }
            else -> {
                // Essai (24h) ET licence expirés : on bloque l'accès à l'application.
                binding.tvStatus.text = getString(R.string.trial_expired_title)
                binding.btnContinueTrial.visibility = android.view.View.GONE
                binding.groupBlocked.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Ouvre une conversation WhatsApp avec le revendeur. Le numéro n'est
     * jamais affiché comme texte à l'écran : il n'existe que dans ce lien.
     *
     * Sur téléphone/tablette : ouverture directe de WhatsApp (comportement
     * inchangé). Sur TV/Box : WhatsApp n'est généralement pas installé, et
     * la télécommande ne permet de toute façon pas de taper un message -
     * on affiche donc un QR code du même lien, à scanner avec un téléphone.
     */
    private fun openWhatsAppContact(deviceKey: String) {
        val phone = getString(R.string.whatsapp_phone_international)
        val message = Uri.encode(
            "Bonjour, je souhaite souscrire à un abonnement SolPlay Pro.\n\nMa clé appareil : $deviceKey"
        )
        val uri = Uri.parse("https://wa.me/$phone?text=$message")

        if (DeviceUtils.isTvDevice(this)) {
            showWhatsAppQrDialog(uri.toString())
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp n'est pas installé sur cet appareil.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Affiche le lien WhatsApp du revendeur sous forme de QR code (construit
     * en code plutôt que via un layout XML séparé, pour un dialogue aussi
     * simple). L'utilisateur scanne avec l'appareil photo de son téléphone,
     * qui ouvre directement la conversation WhatsApp pré-remplie.
     */
    private fun showWhatsAppQrDialog(content: String) {
        val qrBitmap = QrCodeGenerator.generate(content, sizePx = 640)
        if (qrBitmap == null) {
            Toast.makeText(this, "Impossible de générer le QR code.", Toast.LENGTH_LONG).show()
            return
        }

        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = "Scannez avec votre téléphone pour contacter le revendeur sur WhatsApp"
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, 0, 0, padding)
        }
        val qrSizePx = (260 * resources.displayMetrics.density).toInt()
        val imageView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = LinearLayout.LayoutParams(qrSizePx, qrSizePx)
        }
        container.addView(title)
        container.addView(imageView)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun goToApp() {
        startActivity(Intent(this, PlaylistActivity::class.java))
        finish()
    }
}
