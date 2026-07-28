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
import kotlinx.coroutines.launch

/**
 * Écran d'abonnement / paiement direct.
 *
 * Le vrai déblocage se fait uniquement côté serveur après webhook Djèko.
 * L'app, elle, prépare maintenant une "intention de paiement" dans Firebase
 * avec le numéro saisi par le client : si Djèko renvoie ensuite ce même numéro
 * dans le webhook, le serveur peut retrouver automatiquement la bonne clé
 * appareil même sans note/référence manuelle.
 */
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var inputFirstName: EditText
    private lateinit var inputLastName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPhone: EditText
    private lateinit var deviceKey: String
    private val tvPrimaryActionButtons = mutableListOf<View>()

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
            text = "Paiement sécurisé. Votre accès s'active tout seul, dès que le paiement est confirmé."
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
            text = "💡 Astuce : payez avec le même numéro que celui écrit dans le champ \"Téléphone\" ci-dessous. Votre abonnement s'activera automatiquement, sans rien faire d'autre."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
        })
        autoBlock.addView(TextView(this).apply {
            text = "Si jamais l'activation ne se fait pas automatiquement, envoyez ce code à votre revendeur :"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        autoBlock.addView(TextView(this).apply {
            text = deviceKey
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        formStep.addView(autoBlock)

        val infoTitle = TextView(this).apply {
            text = "Vos informations"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        formStep.addView(infoTitle)

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
            text = "Payer"
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

        if (runningOnTv) {
            val qrButton = Button(this).apply {
                text = "📱"
                textSize = 18f
                isAllCaps = false
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
            applyActionButtonStyle(qrButton, focused = false, primary = false, dp = dp)
            qrButton.setOnClickListener { showPaymentQrDialog(plan, deviceKey, dp = dp) }
            qrButton.setOnFocusChangeListener { _, hasFocus ->
                applyActionButtonStyle(qrButton, hasFocus, primary = false, dp = dp)
                card.post { applyCardFocusStyle(card, card.hasFocus(), dp) }
            }
            card.addView(qrButton)
        }

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
            text = "Scannez avec l'appareil photo de votre téléphone pour payer avec Wave, Orange Money, MTN Money, Moov, Djamo ou carte bancaire."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "Pour que ça s'active tout seul, payez avec ce même numéro que celui écrit dans le champ \"Téléphone\" sur la TV."
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_text_on_light_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
        })
        container.addView(TextView(this).apply {
            text = "Si ça ne marche pas tout seul, envoyez ce code à votre revendeur : $deviceKey"
            setTextColor(ContextCompat.getColor(this@SubscriptionActivity, R.color.solplay_orange))
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        AlertDialog.Builder(this)
            .setTitle("${plan.durationLabel} — ${plan.priceLabel}")
            .setView(container)
            .setPositiveButton("Fermer", null)
            .show()
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

        button.isEnabled = false
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val registration = PaymentIntentRegistrar.registerIntent(
                context = this@SubscriptionActivity,
                plan = plan,
                firstName = firstName,
                lastName = lastName,
                email = email,
                phone = phone
            )

            if (!registration.success) {
                progress.visibility = View.GONE
                button.isEnabled = true
                Toast.makeText(this@SubscriptionActivity, registration.message, Toast.LENGTH_LONG).show()
                return@launch
            }

            val result = DjekoPaymentClient.getPaymentUrl(plan, deviceKey)

            progress.visibility = View.GONE
            button.isEnabled = true

            if (result == null) {
                Toast.makeText(
                    this@SubscriptionActivity,
                    "Paiement indisponible pour le moment. Contactez le revendeur via WhatsApp.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            startActivity(
                Intent(this@SubscriptionActivity, PaymentWebViewActivity::class.java).apply {
                    putExtra(PaymentWebViewActivity.EXTRA_PAYMENT_URL, result.paymentUrl)
                }
            )
        }
    }

    companion object {
        private fun isRunningOnTv(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }
    }
}
