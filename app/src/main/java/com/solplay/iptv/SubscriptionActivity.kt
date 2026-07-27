package com.solplay.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Écran d'abonnement / paiement direct - fond blanc (contraste volontaire
 * avec le reste de l'app, thème sombre), affiché uniquement côté app
 * utilisateur (jamais dans le panneau admin, projet séparé).
 *
 * Tarifs confirmés : 1 mois/3000, 3 mois/9000, 6 mois/18000, 12 mois/19000 FCFA
 * (voir SubscriptionPlan.kt).
 *
 * Ne débloque JAMAIS rien elle-même : elle ouvre juste le lien de paiement
 * Djèko (Jeko) du forfait choisi. Le déblocage réel de la licence vient
 * TOUJOURS du webhook Djèko côté serveur qui met à jour Firebase (voir
 * founction netlify/netlify/functions/jeko-webhook.js) - l'app le détecte
 * alors automatiquement via LiveLicenseWatcher, déjà en place. Un
 * utilisateur malveillant ne peut donc jamais se débloquer sans payer,
 * même en modifiant l'app.
 */
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var inputFirstName: EditText
    private lateinit var inputLastName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPhone: EditText
    private lateinit var deviceKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceKey = DeviceKeyManager.getDeviceKey(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val title = TextView(this).apply {
            text = "Choisissez votre forfait"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Paiement sécurisé - accès activé automatiquement après confirmation."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(20))
        }
        root.addView(title)
        root.addView(subtitle)

        // IMPORTANT (voir DjekoPaymentClient.kt) : le lien de paiement Djèko
        // est le même pour tous les clients d'un forfait donné (lien
        // statique, pas d'API dynamique documentée). C'est cette clé,
        // recopiée par le client dans la référence/note du paiement, qui
        // permet au webhook de savoir quel appareil créditer. Sans elle,
        // le paiement finit en attribution manuelle côté admin.
        val keyBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF3E6"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#FFD9AD"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        keyBlock.addView(TextView(this).apply {
            text = "Important : au moment de payer, collez ce code dans le champ \"Référence\" ou \"Note\" du paiement, pour un déblocage automatique."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
        })
        keyBlock.addView(TextView(this).apply {
            text = deviceKey
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        root.addView(keyBlock)

        // Coordonnées du client, collectées une seule fois et réutilisées
        // pour le forfait choisi (utile pour le suivi manuel côté admin
        // si le paiement n'a pas pu être rattaché automatiquement).
        val infoTitle = TextView(this).apply {
            text = "Vos informations"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(infoTitle)

        inputFirstName = buildInputField("Prénom", dp = ::dp)
        inputLastName = buildInputField("Nom", dp = ::dp)
        inputEmail = buildInputField("Email", dp = ::dp).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        inputPhone = buildInputField("Téléphone (ex: +22990123456)", dp = ::dp).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(inputFirstName)
        root.addView(inputLastName)
        root.addView(inputEmail)
        root.addView(inputPhone)

        val plansTitle = TextView(this).apply {
            text = "Forfaits disponibles"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        }
        root.addView(plansTitle)

        val progress = ProgressBar(this).apply { visibility = View.GONE }

        for (plan in SubscriptionPlan.ALL) {
            root.addView(buildPlanCard(plan, deviceKey, progress, dp = ::dp))
        }

        root.addView(progress.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16) }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
        setContentView(scroll)
    }

    /** Champ de saisie simple : le hint sert de label, pas besoin d'un TextView séparé. */
    private fun buildInputField(hint: String, dp: (Int) -> Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#DDDDDD"))
            }
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            // Sans cette ligne, le hint hérite de android:editTextColor du thème
            // global (#F0F0F0, quasi blanc - pensé pour le fond sombre du reste
            // de l'app) : sur le fond clair de CET écran, "Prénom"/"Email"/etc.
            // devient invisible tant que le champ est vide. C'était le bug.
            setHintTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun buildPlanCard(
        plan: SubscriptionPlan,
        deviceKey: String,
        progress: ProgressBar,
        dp: (Int) -> Int
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = plan.durationLabel
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = plan.priceLabel
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })

        val payButton = Button(this).apply {
            text = "Payer"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF7A00"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        payButton.setOnClickListener {
            startPayment(plan, deviceKey, progress, payButton)
        }

        card.addView(textCol)
        card.addView(payButton)

        // Sur TV : le paiement Wave/Orange Money/MTN Money passe par l'appli
        // du fournisseur installée sur LE TÉLÉPHONE du client - impossible à
        // déclencher depuis une TV, quelle que soit l'interface (pas d'appli
        // Wave sur une TV). La carte bancaire, elle, fonctionne très bien
        // dans la WebView même sur TV : le bouton "Payer" reste donc utile
        // tel quel. On ajoute juste, en plus, un bouton QR pour rediriger le
        // paiement mobile money vers le téléphone du client.
        if (isRunningOnTv(this)) {
            val qrButton = Button(this).apply {
                text = "📱"
                textSize = 18f
                setTextColor(Color.parseColor("#FF7A00"))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#FF7A00"))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
            qrButton.setOnClickListener { showPaymentQrDialog(plan, deviceKey, dp = dp) }
            card.addView(qrButton)
        }
        return card
    }

    /**
     * Affiche le lien de paiement du forfait sous forme de QR code, pour que
     * le client le scanne avec son téléphone et termine le paiement
     * Wave/Orange Money/MTN Money là-bas (impossible à faire directement sur
     * une TV). La carte bancaire reste possible sans ça, via le bouton
     * "Payer" classique (WebView), même sur TV.
     */
    private fun showPaymentQrDialog(plan: SubscriptionPlan, deviceKey: String, dp: (Int) -> Int) {
        val url = plan.djekoPaymentUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Paiement indisponible pour le moment. Contactez le revendeur via WhatsApp.", Toast.LENGTH_LONG).show()
            return
        }
        val qrBitmap = QrCodeGenerator.generate(url, sizePx = 512)
        if (qrBitmap == null) {
            Toast.makeText(this, "Impossible de générer le QR code.", Toast.LENGTH_LONG).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        container.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
        })
        container.addView(TextView(this).apply {
            text = "Scannez avec l'appareil photo de votre téléphone pour payer avec Wave, Orange Money, MTN Money ou carte bancaire."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "Important : collez ce code dans la référence/note du paiement, sur votre téléphone :"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
        })
        container.addView(TextView(this).apply {
            text = deviceKey
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 16f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        AlertDialog.Builder(this)
            .setTitle("${plan.durationLabel} — ${plan.priceLabel}")
            .setView(container)
            .setPositiveButton("Fermer", null)
            .show()
    }

    companion object {
        /** Vrai si l'app tourne sur un boîtier/téléviseur Android TV plutôt qu'un téléphone ou une tablette. */
        private fun isRunningOnTv(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }
    }

    private fun startPayment(
        plan: SubscriptionPlan,
        deviceKey: String,
        progress: ProgressBar,
        button: Button
    ) {
        val firstName = inputFirstName.text.toString().trim()
        val lastName = inputLastName.text.toString().trim()
        val email = inputEmail.text.toString().trim()
        val phone = inputPhone.text.toString().trim()

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Merci de remplir vos informations avant de payer.", Toast.LENGTH_LONG).show()
            return
        }

        // Pas d'appel réseau ici : DjekoPaymentClient renvoie directement le
        // lien statique du forfait (voir DjekoPaymentClient.kt pour le
        // pourquoi). On garde quand même button/progress pour un retour
        // visuel cohérent et parce que ça deviendra un vrai appel réseau
        // le jour où Djèko documente une API de création dynamique.
        button.isEnabled = false
        progress.visibility = View.VISIBLE

        val result = DjekoPaymentClient.getPaymentUrl(plan, deviceKey)

        progress.visibility = View.GONE
        button.isEnabled = true

        if (result == null) {
            Toast.makeText(
                this,
                "Paiement indisponible pour le moment. Contactez le revendeur via WhatsApp.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        startActivity(
            Intent(this, PaymentWebViewActivity::class.java).apply {
                putExtra(PaymentWebViewActivity.EXTRA_PAYMENT_URL, result.paymentUrl)
            }
        )
    }
}
