package com.solplay.iptv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        // IMPORTANT : la clé est désormais affichée TELLE QUELLE, sans préfixe
        // "SP-" — l'ancien serveur exigeait "SP-XXXXXXXX" mais DeviceKeyManager
        // produit une clé HEX 16 caractères (UUID tronqué). Le format attendu
        // par SasPay / l'admin a été aligné sur ce nouveau format.
        binding.tvDeviceKey.text = deviceKey
        binding.tvDeviceKeyInOverlay.text = deviceKey

        // Pré-charge aussi le QR code en bitmap (utilisé par l'overlay) :
        QrCodeGenerator.generateForDeviceKey(deviceKey)?.let { qr ->
            binding.ivDeviceKeyQr.setImageBitmap(qr)
        }

        // Bloc contact / support : affiché SOUS le bloc central (et non plus
        // tout en bas).
        binding.tvContactEmail.text = getString(R.string.contact_email)
        binding.tvContactPhone.text = getString(R.string.contact_phone)

        refreshUiState()

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

        LiveCountdown.attach(this) { refreshUiState() }

        binding.btnContinueTrial.setOnClickListener {
            goToApp()
        }

        // ══════════ Bouton « afficher le code » ══════════
        // Affiche l'overlay centré plein écran avec QR + clé. Toucher n'importe
        // où ailleurs ferme l'overlay.
        binding.btnShowCode.setOnClickListener {
            binding.qrOverlay.visibility = View.VISIBLE
        }
        binding.qrOverlay.setOnClickListener {
            binding.qrOverlay.visibility = View.GONE
        }

        // ══════════ Bouton « Copier » ══════════
        // Remplace l'ancien TextView long à sélection manuelle : un bouton
        // court qui copie la clé dans le presse-papier et confirme par toast.
        binding.btnCopyDeviceKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clé appareil SolPlay", deviceKey))
            Toast.makeText(this, "Clé copiée dans le presse-papier", Toast.LENGTH_SHORT).show()
        }

        binding.btnVerifyActivation.setOnClickListener {
            binding.progressBarLicense.visibility = View.VISIBLE
            lifecycleScope.launch {
                val active = TrialManager.checkOnlineLicense(this@LicenseActivity)
                binding.progressBarLicense.visibility = View.GONE
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
                binding.btnContinueTrial.visibility = View.VISIBLE
                binding.groupBlocked.visibility = View.GONE
            }
            trialActive -> {
                val remaining = TrialManager.getRemainingTrialMillis(this)
                binding.tvStatus.text = getString(
                    R.string.trial_active_format,
                    TrialManager.formatDuration(remaining)
                )
                binding.btnContinueTrial.visibility = View.VISIBLE
                binding.groupBlocked.visibility = View.GONE
            }
            else -> {
                binding.tvStatus.text = getString(R.string.trial_expired_title)
                binding.btnContinueTrial.visibility = View.GONE
                binding.groupBlocked.visibility = View.VISIBLE
            }
        }
    }

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
