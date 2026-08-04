package com.solplay.iptv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bandeau de notification affiché directement à l'écran, par-dessus l'écran
 * actuellement affiché - utilisé uniquement sur TV/Box (voir DeviceUtils),
 * où les notifications système Android classiques (NotificationCompat) ne
 * sont généralement pas visibles : pas de tiroir de notifications comme sur
 * téléphone, souvent aucune UI système dédiée sur les box génériques.
 *
 * Disparaît tout seul après 10 secondes, ou immédiatement si l'utilisateur
 * appuie sur la croix de fermeture (cliquable à la télécommande, pas
 * seulement au doigt/souris).
 *
 * Suit l'activité au premier plan via SolPlayApplication.registerActivityLifecycleCallbacks
 * - sans ça, impossible de savoir où ajouter le bandeau à l'écran quand une
 * notification arrive (l'app peut être sur n'importe quel écran : accueil,
 * lecteur vidéo, etc.).
 */
object TvNotificationBanner {

    private var currentActivity: Activity? = null
    private val handler = Handler(Looper.getMainLooper())
    private const val AUTO_DISMISS_MS = 10_000L

    fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    fun show(title: String, body: String) {
        val activity = currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread { showInternal(activity, title, body) }
    }

    private fun showInternal(activity: Activity, title: String, body: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val closeButton = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(10), dp(2), dp(2), dp(2))
            isClickable = true
            isFocusable = true
            // Highlight visible au focus télécommande / au clic - drawable
            // système standard, pas besoin d'en créer un dédié.
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#40FFFFFF")),
                null, null
            )
        }

        val titleView = TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(closeButton)
        }

        val bodyView = TextView(activity).apply {
            text = body
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1A1A1A"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#FF7A00"))
            }
            addView(headerRow)
            addView(bodyView)
        }

        val wrapper = FrameLayout(activity)
        val cardParams = FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(24)
            marginEnd = dp(24)
        }
        wrapper.addView(card, cardParams)
        wrapper.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        root.addView(wrapper)

        fun dismiss() {
            if (wrapper.parent === root) root.removeView(wrapper)
        }

        val autoDismiss = Runnable { dismiss() }
        closeButton.setOnClickListener {
            handler.removeCallbacks(autoDismiss)
            dismiss()
        }

        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)

        // Focus immédiat sur la croix : permet de la fermer tout de suite à
        // la télécommande sans devoir naviguer jusqu'à elle au préalable.
        closeButton.post { closeButton.requestFocus() }
    }
}
