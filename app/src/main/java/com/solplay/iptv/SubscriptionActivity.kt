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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Écran d'abonnement / paiement direct.
 *
 * ── Changement d'agrégateur : Djèko → SasPay ───────────────────────────────
 * AVANT : chaque forfait ouvrait un lien de paiement STATIQUE Djèko, identique
 * pour tous les clients. Il fallait donc pré-enregistrer une « intention de
 * paiement » avec le numéro de téléphone du client, pour que le webhook puisse
 * tenter de rattacher le paiement à un appareil (méthode fragile).
 *
 * APRÈS (SasPay) : la CLÉ APPAREIL est la seule identité d'achat. Elle est
 * envoyée à la fonction serveur `create-checkout`, qui crée une session SasPay
 * dynamique et l'inscrit dans la description + les métadonnées du paiement. Le
 * webhook active donc exactement l'appareil qui a payé — sans aucun numéro de
 * téléphone. Le numéro saisi ci-dessous ne sert plus qu'au formulaire de
 * paiement SasPay (le payeur doit être joignable par son opérateur mobile money).
 *
 * Le vrai déblocage se fait uniquement côté serveur, après confirmation du
 * paiement (webhook SasPay, avec `check-payment` en filet de sécurité).
 */
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var inputFirstName: EditText
    private lateinit var inputLastName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPhone: EditText
    private lateinit var deviceKey: String
    private val tvPrimaryActionButtons = mutableListOf<View>()
    private var currentIntentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceKey = DeviceKeyManager.getDeviceKey(this)
        val runningOnTv = isRunningOnTv(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // --- Étape 1 : formulaire d'informations ---
        val formStep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val title = TextView(this).apply {
            text = "Vos informations"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Paiement sécurisé via SasPay. Votre accès s'active tout seul, dès que le paiement est confirmé."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(20))
        }
        formStep.addView(title)
        formStep.addView(subtitle)

        val autoBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF3E6"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#FFD9AD"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        autoBlock.addView(TextView(this).apply {
            text = "💡 Clé de votre appareil (pré-remplie, à ne pas modifier). Elle identifie votre paiement : c'est avec elle que votre accès sera débloqué automatiquement."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
        })
        autoBlock.addView(TextView(this).apply {
            text = deviceKey
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        autoBlock.addView(TextView(this).apply {
            text = "Si l'activation automatique ne se fait pas, envoyez ce code à votre revendeur :"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
        formStep.addView(autoBlock)

        inputFirstName = buildInputField("Prénom", dp = ::dp)
        inputLastName = buildInputField("Nom", dp = ::dp)
        inputEmail = buildInputField("Email", dp = ::dp).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        inputPhone = buildInputField("Téléphone du payeur (ex: +22990123456)", dp = ::dp).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        formStep.addView(inputFirstName)
        formStep.addView(inputLastName)
        formStep.addView(inputEmail)
        formStep.addView(inputPhone)

        val continueButton = Button(this).apply {
            text = "Continuer"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF7A00"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        formStep.addView(continueButton)

        val formScroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(formStep)
        }

        // --- Étape 2 : grille de forfaits (construite mais pas affichée tout de suite) ---
        val plansStep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = TextView(this).apply {
            text = "←  Modifier mes informations"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(8))
        }
        backRow.addView(backButton)
        plansStep.addView(backRow)

        val plansTitle = TextView(this).apply {
            text = "Forfaits disponibles"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        plansStep.addView(plansTitle)

        val progress = ProgressBar(this).apply { visibility = View.GONE }

        for (plan in SubscriptionPlan.ALL) {
            plansStep.addView(buildPlanCard(plan, deviceKey, progress, runningOnTv, dp = ::dp))
        }

        plansStep.addView(progress.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        })

        val plansScroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            visibility = View.GONE
            addView(plansStep)
        }

        // Conteneur commun : une seule des deux étapes visible à la fois.
        val container = FrameLayout(this).apply {
            addView(formScroll)
            addView(plansScroll)
        }
        setContentView(container)

        continueButton.setOnClickListener {
            val firstName = inputFirstName.text.toString().trim()
            val lastName = inputLastName.text.toString().trim()
            val email = inputEmail.text.toString().trim()
            val phone = inputPhone.text.toString().trim()
            if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Merci de remplir vos informations avant de continuer.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            formScroll.visibility = View.GONE
            plansScroll.visibility = View.VISIBLE
            if (runningOnTv) {
                plansScroll.post { tvPrimaryActionButtons.firstOrNull()?.requestFocus() }
            }
        }

        backButton.setOnClickListener {
            plansScroll.visibility = View.GONE
            formScroll.visibility = View.VISIBLE
        }

        if (runningOnTv) {
            formScroll.post { inputFirstName.requestFocus() }
        }
    }

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
            setHintTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun buildPlanCard(
        plan: SubscriptionPlan,
        deviceKey: String,
        progress: ProgressBar,
        runningOnTv: Boolean,
        dp: (Int) -> Int
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        applyCardFocusStyle(card, focused = false, dp = dp)

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
            text = "Payer en ligne"
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        applyActionButtonStyle(payButton, focused = false, primary = true, dp = dp)
        payButton.setOnClickListener {
            startPayment(plan, deviceKey, progress, payButton)
        }
        payButton.setOnFocusChangeListener { _, hasFocus ->
            applyActionButtonStyle(payButton, hasFocus, primary = true, dp = dp)
            card.post { applyCardFocusStyle(card, card.hasFocus(), dp) }
        }

        if (runningOnTv) {
            tvPrimaryActionButtons += payButton
        }

        card.addView(textCol)
        card.addView(payButton)

        return card
    }

    private fun applyCardFocusStyle(card: LinearLayout, focused: Boolean, dp: (Int) -> Int) {
        card.background = GradientDrawable().apply {
            setColor(if (focused) Color.parseColor("#FFF0E0") else Color.parseColor("#F5F5F5"))
            cornerRadius = dp(12).toFloat()
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) Color.parseColor("#FF7A00") else Color.parseColor("#E0E0E0")
            )
        }
        card.scaleX = if (focused) 1.015f else 1f
        card.scaleY = if (focused) 1.015f else 1f
        card.elevation = if (focused) dp(6).toFloat() else 0f
    }

    private fun applyActionButtonStyle(button: Button, focused: Boolean, primary: Boolean, dp: (Int) -> Int) {
        if (primary) {
            button.setTextColor(Color.WHITE)
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (focused) "#E56700" else "#FF7A00"))
                cornerRadius = dp(8).toFloat()
                if (focused) setStroke(dp(2), Color.WHITE)
            }
        } else {
            button.setTextColor(if (focused) Color.WHITE else Color.parseColor("#FF7A00"))
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (focused) "#FF7A00" else "#FFFFFF"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (focused) 2 else 1), Color.parseColor("#FF7A00"))
            }
        }
        button.scaleX = if (focused) 1.06f else 1f
        button.scaleY = if (focused) 1.06f else 1f
        button.elevation = if (focused) dp(4).toFloat() else 0f
    }

    /**
     * Lance le paiement SasPay pour le forfait choisi.
     *
     * 1. la fonction serveur crée la session de paiement (clé appareil incluse) ;
     * 2. la page de paiement SasPay s'ouvre dans le WebView intégré (le client
     *    choisit son réseau : Wave, Orange Money, MTN, Moov, Djamo, carte) ;
     * 3. l'écran interroge périodiquement le serveur : dès que le paiement est
     *    confirmé, la licence de cet appareil est activée et l'utilisateur peut
     *    appuyer sur « Vérifier mon activation » (ou revenir : la vérification
     *    automatique de LicenseActivity le fera toute seule).
     */
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

        button.isEnabled = false
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = SaspayPaymentClient.createCheckout(
                deviceKey = deviceKey,
                planId = plan.id,
                firstName = firstName,
                lastName = lastName,
                email = email,
                phone = phone
            )

            progress.visibility = View.GONE
            button.isEnabled = true

            if (result.checkoutUrl == null) {
                Toast.makeText(
                    this@SubscriptionActivity,
                    result.error ?: "Paiement indisponible pour le moment. Contactez le revendeur via WhatsApp.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            currentIntentId = result.intentId
            startActivity(
                Intent(this@SubscriptionActivity, PaymentWebViewActivity::class.java).apply {
                    putExtra(PaymentWebViewActivity.EXTRA_PAYMENT_URL, result.checkoutUrl)
                }
            )

            // Filet de sécurité : tant que cet écran est affiché, on vérifie
            // toutes les 5 secondes si le paiement est passé (au cas où le
            // webhook serait retardé ou perdu). Dès que c'est confirmé, la
            // licence de cet appareil est activée et on passe à l'application.
            while (true) {
                delay(5_000)
                val status = SaspayPaymentClient.checkPayment(deviceKey, currentIntentId)
                if (status.activated) {
                    TrialManager.checkOnlineLicense(this@SubscriptionActivity)
                    Toast.makeText(
                        this@SubscriptionActivity,
                        "Paiement confirmé ! Votre abonnement est activé.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this@SubscriptionActivity, PlaylistActivity::class.java))
                    finish()
                    break
                }
            }
        }
    }

    companion object {
        private fun isRunningOnTv(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }
    }
}
