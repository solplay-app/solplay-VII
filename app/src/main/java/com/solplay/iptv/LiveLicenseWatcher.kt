package com.solplay.iptv

import android.content.Context
import android.content.Intent
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Écoute EN CONTINU (websocket Firebase déjà ouvert en permanence, pas un
 * sondage périodique) le statut de licence de cet appareil
 * (`licenses/{deviceKey}`), pour réagir immédiatement quand l'admin
 * suspend/modifie la clé - y compris pendant que l'utilisateur regarde une
 * chaîne ou navigue dans l'app.
 *
 * Avant ce fichier, `licenses/{deviceKey}` n'était vérifié que :
 * - à l'écran de licence (bloquant, avant activation) - toutes les 10s
 * - à l'écran "Compte"
 * - une fois par heure (RemainingTimeReminderWorker)
 * ...jamais pendant l'utilisation normale (accueil, chaînes, lecteur), donc
 * une suspension pouvait mettre jusqu'à 1h à être détectée.
 *
 * Démarré une seule fois au lancement du process (SolPlayApplication) et
 * reste actif tant que l'app tourne, quel que soit l'écran affiché.
 */
object LiveLicenseWatcher {

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        val deviceKey = DeviceKeyManager.getDeviceKey(appContext)
        val ref = FirebaseDatabase.getInstance().getReference("licenses").child(deviceKey)

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java) ?: 0L
                val planLabel = snapshot.child("planLabel").getValue(String::class.java)

                // Capturé AVANT d'écraser le cache local, pour détecter la
                // transition valide -> invalide (et ne réagir que sur celle-ci,
                // pas sur chaque écriture Firebase mineure comme un simple
                // renouvellement qui prolonge juste expiresAt).
                val wasValid = TrialManager.isLicensed(appContext)

                TrialManager.applyLicenseSnapshot(appContext, active, expiresAt, planLabel)
                val stillValid = TrialManager.isLicenseSnapshotValid(appContext, active, expiresAt)

                if (wasValid && !stillValid) {
                    kickToLicenseScreen(appContext)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Pas de réseau/permissions Firebase : on ne change rien,
                // l'app continue avec le dernier statut connu localement.
            }
        })
    }

    /** Éjecte immédiatement vers l'écran de licence, en vidant la pile (impossible d'y revenir avec "Retour"). */
    private fun kickToLicenseScreen(context: Context) {
        val reason = "Votre licence SolPlay a été suspendue par l'administrateur."

        if (DeviceUtils.isTvDevice(context)) {
            TvNotificationBanner.show("SolPlay", reason)
        }

        val intent = Intent(context, LicenseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(LicenseActivity.EXTRA_REASON, reason)
        }
        context.startActivity(intent)
    }
}
