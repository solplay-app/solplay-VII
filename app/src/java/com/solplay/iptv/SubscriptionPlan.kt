package com.solplay.iptv

/**
 * Un forfait affiché sur l'écran d'abonnement (SubscriptionActivity).
 * Tarifs confirmés par l'utilisateur : 1 mois/3000, 3 mois/9000,
 * 6 mois/18000, 12 mois/19000 FCFA.
 *
 * Paiement via Djèko (Jeko) - service Zayono abandonné. Voir
 * DjekoPaymentClient.kt pour le détail : Djèko n'a pas d'API documentée
 * de création dynamique de paiement, donc chaque forfait pointe vers un
 * lien de paiement STATIQUE créé une fois dans le Jeko Cockpit.
 */
data class SubscriptionPlan(
    val id: String,
    val durationLabel: String,
    val priceLabel: String,
    val amount: Long,       // montant en FCFA (doit correspondre EXACTEMENT au montant du lien Djèko, sinon le webhook ne reconnaîtra pas le forfait - voir PLAN_BY_AMOUNT dans jeko-webhook.js)
    val currency: String = "XOF",
    /**
     * TODO : coller ici l'URL du lien de paiement Djèko créé pour ce
     * forfait précis (Jeko Cockpit > Liens de paiement). Tant que c'est
     * null, le bouton "Payer" de ce forfait affiche un message
     * "indisponible" au lieu de planter.
     */
    val djekoPaymentUrl: String? = null
) {
    companion object {
        val ALL = listOf(
            SubscriptionPlan(
                id = "1m", durationLabel = "1 mois", priceLabel = "3 000 FCFA", amount = 3000,
                djekoPaymentUrl = "https://pay.jeko.africa/pl/cf19d1f8-bef8-4046-ab66-ce298cb33da0"
            ),
            SubscriptionPlan(
                id = "3m", durationLabel = "3 mois", priceLabel = "9 000 FCFA", amount = 9000,
                djekoPaymentUrl = "https://pay.jeko.africa/pl/3a327291-e720-4ac1-aa0e-7b9101071781"
            ),
            SubscriptionPlan(
                id = "6m", durationLabel = "6 mois", priceLabel = "18 000 FCFA", amount = 18000,
                djekoPaymentUrl = "https://pay.jeko.africa/pl/17fa9fd5-2a6f-4641-9b25-f56a9ae7ff91"
            ),
            SubscriptionPlan(
                id = "12m", durationLabel = "12 mois", priceLabel = "19 000 FCFA", amount = 19000,
                djekoPaymentUrl = "https://pay.jeko.africa/pl/1bc939fd-1762-474f-b236-39c2a1e3a34b"
            )
        )
    }
}
