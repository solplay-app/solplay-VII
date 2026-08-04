package com.solplay.iptv

/**
 * Point d'accès ultra-simple aux liens de paiement Djèko configurés pour
 * SolPlay.
 *
 * On conserve ici les liens statiques créés dans le Cockpit Djèko, un par
 * forfait. L'amélioration d'activation automatique n'est donc PAS portée par
 * l'URL elle-même : elle est désormais gérée par le duo suivant :
 *
 * 1) l'app écrit une intention de paiement dans Firebase avant d'ouvrir le lien
 *    (voir PaymentIntentRegistrar.kt), avec le numéro du client ;
 * 2) le webhook serveur tente ensuite de faire la correspondance entre le
 *    numéro du payeur renvoyé par Djèko et cette intention, ce qui permet de
 *    créditer automatiquement la bonne clé appareil même sans référence/note.
 *
 * Si, plus tard, SolPlay migre vers l'API Partenaire JEKO pour créer des
 * demandes de paiement dynamiques par transaction, c'est ce fichier qui devra
 * être remplacé par un vrai client HTTP côté serveur / backend.
 */
object DjekoPaymentClient {

    data class PaymentInitResult(
        val paymentUrl: String,
        val transactionRef: String
    )

    fun getPaymentUrl(plan: SubscriptionPlan, deviceKey: String): PaymentInitResult? {
        val url = plan.djekoPaymentUrl?.takeIf { it.isNotBlank() } ?: return null
        return PaymentInitResult(
            paymentUrl = url,
            transactionRef = "solplay-$deviceKey-${System.currentTimeMillis()}"
        )
    }
}
