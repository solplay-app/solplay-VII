package com.solplay.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Configuration des fonctions serveur SolPlay (hébergées sur Netlify, les
 * mêmes que celles utilisées par le panneau admin).
 *
 * ⚠️ À CONFIGURER : SITE_BASE_URL doit pointer vers le site Netlify qui
 * héberge le panneau admin et son dossier netlify/functions.
 */
object SolPlayBackend {
    const val SITE_BASE_URL = "https://solplayadmin4.netlify.app"
    val CREATE_CHECKOUT_URL: String get() = "$SITE_BASE_URL/.netlify/functions/create-checkout"
    val CHECK_PAYMENT_URL: String get() = "$SITE_BASE_URL/.netlify/functions/check-payment"
    const val APP_SOURCE = "android"
}

/**
 * Client de paiement SasPay (via les fonctions serveur SolPlay).
 *
 * Remplace DjekoPaymentClient (liens statiques Djèko), entièrement retiré.
 *
 * Flux :
 *   1. l'app envoie la CLÉ APPAREIL + le forfait choisi à create-checkout ;
 *   2. le serveur crée une session SasPay hébergée et renvoie `checkoutUrl` ;
 *   3. l'app ouvre cette page (WebView) : le client paie avec Wave, Orange
 *      Money, MTN, Moov, Djamo ou carte bancaire ;
 *   4. le webhook SasPay active la licence de cette clé appareil. En parallèle,
 *      l'app appelle check-payment en boucle : garantie de déblocage même si
 *      le webhook est retardé ou perdu.
 *
 * La clé secrète SasPay (sk_live_...) n'est JAMAIS embarquée dans l'APK : elle
 * resterait extractible. Toutes les opérations sensibles passent par le serveur.
 */
object SaspayPaymentClient {

    data class CheckoutResult(
        val checkoutUrl: String?,
        val intentId: String?,
        val error: String?
    )

    data class StatusResult(
        val activated: Boolean,
        val expiresAt: Long,
        val planLabel: String?,
        val rawStatus: String?,
        val error: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Crée une session de paiement SasPay pour cet appareil et ce forfait. */
    suspend fun createCheckout(
        deviceKey: String,
        planId: String,
        firstName: String,
        lastName: String,
        email: String,
        phone: String
    ): CheckoutResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("deviceKey", deviceKey)
                put("planId", planId)
                put("firstName", firstName)
                put("lastName", lastName)
                put("email", email)
                put("phone", phone)
                put("source", SolPlayBackend.APP_SOURCE)
            }
            val request = Request.Builder()
                .url(SolPlayBackend.CREATE_CHECKOUT_URL)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                    return@withContext CheckoutResult(
                        null, null,
                        json.optString("error").takeIf { it.isNotBlank() }
                            ?: "Impossible de préparer le paiement (erreur ${response.code})."
                    )
                }
                CheckoutResult(
                    checkoutUrl = json.optString("checkoutUrl").takeIf { it.isNotBlank() },
                    intentId = json.optString("intentId").takeIf { it.isNotBlank() },
                    error = null
                )
            }
        } catch (e: Exception) {
            CheckoutResult(null, null, "Connexion au service de paiement impossible : ${e.message ?: "erreur réseau"}")
        }
    }

    /** Vérifie activement le paiement et fait créditer la licence si besoin. */
    suspend fun checkPayment(deviceKey: String, intentId: String? = null): StatusResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("deviceKey", deviceKey)
                    if (!intentId.isNullOrBlank()) put("intentId", intentId)
                }
                val request = Request.Builder()
                    .url(SolPlayBackend.CHECK_PAYMENT_URL)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                    if (!response.isSuccessful) {
                        return@withContext StatusResult(
                            false, 0L, null, null,
                            json.optString("error").takeIf { it.isNotBlank() }
                                ?: "Vérification impossible (erreur ${response.code})."
                        )
                    }
                    StatusResult(
                        activated = json.optBoolean("activated", false),
                        expiresAt = json.optLong("expiresAt", 0L),
                        planLabel = json.optString("planLabel").takeIf { it.isNotBlank() },
                        rawStatus = json.optString("saspayStatus").takeIf { it.isNotBlank() },
                        error = null
                    )
                }
            } catch (e: Exception) {
                StatusResult(false, 0L, null, null, "Vérification impossible : ${e.message ?: "erreur réseau"}")
            }
        }
}
