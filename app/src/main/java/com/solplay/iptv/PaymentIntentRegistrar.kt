package com.solplay.iptv

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pré-enregistre côté Firebase l'intention de paiement du client AVANT
 * l'ouverture du lien Djèko.
 *
 * Objectif : si Djèko renvoie ensuite le numéro du payeur dans le webhook
 * (`counterpartIdentifier`), le serveur peut rattacher automatiquement le
 * paiement à l'appareil du client même si celui-ci n'a rien collé dans
 * "Référence" / "Note".
 *
 * Cela fonctionne surtout pour les paiements Mobile Money effectués avec le
 * même numéro que celui saisi dans l'app. En cas d'ambiguïté (même numéro,
 * même montant, plusieurs appareils en attente), le webhook bascule proprement
 * en assignation manuelle au lieu de créditer le mauvais appareil.
 */
object PaymentIntentRegistrar {

    data class RegistrationResult(
        val success: Boolean,
        val message: String
    )

    suspend fun registerIntent(
        context: Context,
        plan: SubscriptionPlan,
        firstName: String,
        lastName: String,
        email: String,
        phone: String
    ): RegistrationResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val normalizedPhone = normalizePhone(phone)
            if (normalizedPhone.isNullOrBlank()) {
                return@withContext RegistrationResult(
                    false,
                    "Numéro de téléphone invalide."
                )
            }

            val deviceKey = DeviceKeyManager.getDeviceKey(context)
            val customerName = listOf(firstName.trim(), lastName.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")

            val data = hashMapOf<String, Any?>(
                "deviceKey" to deviceKey,
                "planId" to plan.id,
                "planLabel" to plan.durationLabel,
                "amount" to plan.amount,
                "currency" to plan.currency,
                "customerFirstName" to firstName.trim(),
                "customerLastName" to lastName.trim(),
                "customerName" to customerName,
                "customerEmail" to email.trim(),
                "customerPhone" to phone.trim(),
                "phoneNormalized" to normalizedPhone,
                "status" to "pending",
                "createdAt" to isoNow(),
                "createdAtServer" to ServerValue.TIMESTAMP
            )

            FirebaseDatabase.getInstance()
                .reference
                .child("payment_intents")
                .push()
                .setValue(data)
                .await()

            RegistrationResult(true, "Intent enregistré")
        } catch (e: Exception) {
            RegistrationResult(
                false,
                "Impossible de préparer l'activation automatique : ${e.message ?: "erreur inconnue"}"
            )
        }
    }

    private fun normalizePhone(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 8) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    private fun isoNow(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.US
    ).format(Date())
}
