package com.solplay.iptv

/**
 * Remplace ZayonoPaymentClient (service Zayono entièrement abandonné).
 *
 * ─────────────────────────────────────────────────────────────────────
 * Pourquoi pas d'appel API ici, contrairement à l'ancien ZayonoPaymentClient ?
 *
 * La documentation Djèko (Jeko) fournie par l'utilisateur couvre
 * uniquement la RÉCEPTION des paiements (webhooks, vérification de
 * signature HMAC-SHA256, structure du payload transaction.completed) -
 * voir jeko-webhook.js côté "founction netlify". Aucune API "créer un
 * paiement" / "générer un lien de paiement dynamique" n'y est documentée.
 *
 * Solution retenue en attendant cette info : un lien de paiement STATIQUE
 * par forfait, créé une fois pour toutes dans le Jeko Cockpit (Paramètres
 * > Liens de paiement, ou équivalent), et collé dans SubscriptionPlan.kt.
 * L'app n'a donc rien à "créer" - elle ouvre juste le bon lien selon le
 * forfait choisi. Le webhook, lui, sait que le paiement est réussi et pour
 * quel montant ; il retrouve l'appareil à créditer via la clé appareil que
 * SubscriptionActivity demande au client de coller dans la référence/note
 * du paiement (voir DEVICE_KEY_PATTERN dans jeko-webhook.js).
 *
 * Si Djèko fournit un jour une vraie API de création de paiement
 * dynamique (avec montant/référence/metadata par transaction), ce fichier
 * est l'endroit à modifier : on retrouverait alors le même genre d'appel
 * HTTP que l'ancien ZayonoPaymentClient, mais avec les bons champs de LEUR
 * documentation - à ne jamais deviner à nouveau sans la doc en main.
 * ─────────────────────────────────────────────────────────────────────
 */
object DjekoPaymentClient {

    data class PaymentInitResult(
        val paymentUrl: String,
        val transactionRef: String
    )

    /**
     * Renvoie le lien de paiement statique du [plan], ou null si l'admin
     * n'a pas encore renseigné ce lien dans SubscriptionPlan.kt.
     * [deviceKey] n'est pas utilisé pour construire l'URL (lien statique,
     * pas de paramètre dynamique) - il sert uniquement d'indication pour
     * SubscriptionActivity, qui l'affiche au client pour qu'il la
     * renseigne lui-même en référence du paiement.
     */
    fun getPaymentUrl(plan: SubscriptionPlan, deviceKey: String): PaymentInitResult? {
        val url = plan.djekoPaymentUrl?.takeIf { it.isNotBlank() } ?: return null
        return PaymentInitResult(
            paymentUrl = url,
            transactionRef = "solplay-$deviceKey-${System.currentTimeMillis()}"
        )
    }
}
