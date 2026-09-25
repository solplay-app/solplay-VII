package com.solplay.iptv

/**
 * Un forfait affiché sur l'écran d'abonnement (SubscriptionActivity).
 * Tarifs : 1 mois/3000, 3 mois/9000, 6 mois/18000, 12 mois/19000 FCFA.
 *
 * ── Changement majeur (agrégateur de paiement) ─────────────────────────────
 * AVANT : Djèko (Jeko). Chaque forfait pointait vers un LIEN DE PAIEMENT
 * STATIQUE créé une fois à la main dans le Jeko Cockpit. Comme ce lien était
 * le même pour tous les clients d'un même forfait, le serveur ne savait pas
 * quel appareil créditer : il devait deviner (numéro de téléphone du payeur).
 *
 * APRÈS : SasPay. Le paiement est créé DYNAMIQUEMENT pour chaque appareil via
 * la fonction serveur `create-checkout` (voir SaspayPaymentClient.kt), qui est
 * la seule à posséder la clé secrète SasPay. La clé appareil est envoyée à la
 * création de la session et se retrouve dans la description + les métadonnées
 * du paiement : le webhook active donc exactement l'appareil qui a payé.
 *
 * Il n'y a donc PLUS d'URL de paiement dans ce fichier (djekoPaymentUrl a été
 * retiré), et le montant ne sert plus qu'à l'affichage : le montant réellement
 * facturé est appliqué côté serveur, ce qui interdit toute manipulation de prix.
 *
 * Doit rester synchronisé avec :
 *  - create-checkout.js / saspay-webhook.js (PLANS / PLAN_BY_AMOUNT)
 *  - SolPlayPlans.kt (application Windows)
 */
data class SubscriptionPlan(
    val id: String,
    val durationLabel: String,
    val priceLabel: String,
    /** Montant en FCFA — purement informatif côté app (le serveur fait foi). */
    val amount: Long,
    val currency: String = "XOF"
) {
    companion object {
        val ALL = listOf(
            SubscriptionPlan(
                id = "1m", durationLabel = "1 mois", priceLabel = "3 000 FCFA", amount = 3000
            ),
            SubscriptionPlan(
                id = "3m", durationLabel = "3 mois", priceLabel = "9 000 FCFA", amount = 9000
            ),
            SubscriptionPlan(
                id = "6m", durationLabel = "6 mois", priceLabel = "18 000 FCFA", amount = 18000
            ),
            SubscriptionPlan(
                id = "12m", durationLabel = "12 mois", priceLabel = "19 000 FCFA", amount = 19000
            )
        )
    }
}
