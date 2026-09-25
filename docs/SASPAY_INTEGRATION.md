# Intégration de l'agrégateur SasPay (remplace Djèko/Jeko)

## Ce qui a changé

| Avant (Djèko) | Après (SasPay) |
| --- | --- |
| Liens de paiement **statiques** créés à la main dans le Jeko Cockpit, un par forfait | Session de paiement **dynamique** créée par appel serveur, à chaque achat |
| Le serveur devait **deviner l'appareil** (numéro de téléphone du payeur, ou clé collée dans « Référence ») | La **clé appareil** est envoyée à la création du paiement (description + métadonnées) : l'appareil est identifié de façon certaine |
| Clés `X-API-KEY` / `X-API-KEY-ID`, signature `Jeko-Signature` (HMAC sur le corps seul) | Clé secrète `Bearer sk_live_...`, signature `X-Webhook-Signature` (**HMAC-SHA256 sur `timestamp.body`**) |
| Événement `transaction.completed`, `transactionType: "payment"` | Événements `transaction.success` / `transaction.failed` / … avec `type: "PAIEMENT"`, `status: "SUCCESS"` |

## Contrat SasPay utilisé (vérifié dans la doc officielle)

- Base URL : `https://api.saspay.me/api/v1`
- Authentification : header `Authorization: Bearer sk_live_xxx` (scope `PAYIN` ou `BOTH`)
- **Créer une session de paiement** : `POST /checkout-sessions/`
  - Corps : `amount` (chaîne, ex. `"3000.00"`), `currency: "XOF"`, `description`,
    `country`, `customer_email`, `customer_name`, `customer_phone`, `return_url`, `metadata`
  - Réponse : `{ id, slug, checkout_url, status: "PENDING", … }`
  - ⚠️ `Idempotency-Key` non pris en charge sur cet endpoint (un double clic crée
    deux sessions — sans conséquence financière : une session non payée ne débite rien)
- **Vérifier un paiement** : `GET /checkout-sessions/{id}/` puis, en cas de doute,
  `GET /payments/{id}/verify/` (la doc conseille de ne jamais se fier à un statut mémorisé)
- **Webhook** : payload `{ "event": "transaction.success", "data": { id, reference,
  type, status, amount, fee, charged, net_amount, fee_charge_mode, currency,
  country, network, msisdn } }`
  - En-têtes : `X-Webhook-Signature` (hex minuscules), `X-Webhook-Timestamp`, `X-Webhook-Event`
  - Signature = `HMAC_SHA256(secret, "{timestamp}.{corps brut}")`
  - Réponse attendue : HTTP 2xx, sous 5 s (jusqu'à 3 tentatives)

## Pourquoi la clé secrète n'est jamais dans l'application

La clé `sk_live_...` donne accès à **tout** le compte marchand. Embarquée dans un
APK ou un `.exe`, elle serait extractible en quelques minutes. L'application
n'appelle donc **jamais** SasPay directement : elle appelle deux fonctions
serveur Netlify (celles du panneau admin), qui seules détiennent la clé.

## Fonctions serveur (dossier `panneau-admin/netlify/functions`)

| Fonction | Rôle |
| --- | --- |
| `create-checkout.js` | Crée la session SasPay pour (clé appareil, forfait) et renvoie `checkoutUrl` |
| `saspay-webhook.js` | Reçoit la notification SasPay, vérifie la signature, **active la licence de la clé appareil** |
| `check-payment.js` | Vérification active appelée par les apps (garantie si le webhook est en retard/perdu) |
| `assign-payment.js` | Affectation **manuelle** d'un paiement non reconnu à une clé appareil (depuis le panneau) |

## Variables d'environnement Netlify à définir

```
SASPAY_SECRET_KEY=sk_live_xxxxxxxxxxxxxxxx
SASPAY_WEBHOOK_SECRET=<secret de signature du webhook SasPay>
SASPAY_API_BASE=https://api.saspay.me/api/v1      (optionnel, valeur par défaut)
SASPAY_RETURN_URL=https://solplay.app/paiement-termine   (optionnel)
FIREBASE_SERVICE_ACCOUNT=<déjà en place>
FIREBASE_DATABASE_URL=<déjà en place>
```

Le webhook s'enregistre **uniquement** depuis le tableau de bord SasPay
(https://app.saspay.me) — la doc précise qu'il n'existe pas d'API de création :

```
URL du webhook : https://<ton-site>.netlify.app/.netlify/functions/saspay-webhook
Événements     : transaction.success (les autres sont ignorés proprement)
```

## Flux d'activation automatique (100 % par clé appareil)

```
App (Android ou Windows)
   │  deviceKey + planId + coordonnées
   ▼
create-checkout.js ──► POST /checkout-sessions/ (clé secrète côté serveur)
   │  checkout_url
   ▼
App ouvre la page SasPay (WebView Android / navigateur Windows)
   │  le client paie (Wave, Orange Money, MTN, Moov, Djamo, carte)
   ▼
SasPay ──► webhook saspay-webhook.js  (voie principale)
        └─► check-payment.js appelé par l'app  (filet de sécurité)
   │  creditLicense(deviceKey, forfait) → licenses/{deviceKey}
   ▼
App : LicenseActivity (Android) / LicenseScreen (Windows)
     détecte l'activation (Firebase) et se débloque automatiquement
```

Aucun numéro de téléphone n'intervient dans l'identification de l'appareil : il
ne sert qu'au formulaire de paiement. Les forfaits sont appliqués **côté serveur**
(montant non fourni par le client), ce qui empêche toute manipulation de prix.

## Règles de sécurité appliquées au webhook

1. Signature HMAC vérifiée sur le **corps brut** (jamais sur le JSON ré-analysé).
2. Idempotence : un paiement déjà crédité (`processed: true`) n'est **jamais** crédité deux fois.
3. Seuls les paiements entrants réussis créditent une licence.
4. En cas de doute (montant inconnu, clé introuvable), le paiement est enregistré
   avec `needsManualAssignment: true` pour une affectation manuelle — jamais crédité au hasard.
5. `timingSafeEqual` utilisé pour la comparaison de signature (anti timing-attack).

## Tarification SasPay (à titre indicatif)

Les frais sont **réseau par réseau** et propres à chaque compte marchand : ils
ne sont pas publics dans la documentation. Le seul moyen fiable de les connaître
est l'endpoint authentifié `GET /pricing/my-rates/`, qui renvoie, pour chaque
(pays, réseau) : `percent`, `fixed`, `floor_amount`, `cap_amount`, `fee_charge_mode`,
et `otp_required` / `otp_instructions`. Exemple renvoyé par la doc :

```json
{ "country_code": "CI", "currency": "XOF", "network_code": "orange_ci",
  "payin": { "available": true, "otp_required": true,
             "tiers": [{ "min_amount": "0.00", "max_amount": "500000.00",
                         "percent": "2.500", "fixed": "0.00",
                         "floor_amount": "50.00", "cap_amount": null,
                         "fee_charge_mode": "ADD_ON" }] } }
```

Deux options existent pour l'imputation des frais (`fee_charge_mode`) :
- `ADD_ON` : les frais s'ajoutent au montant débité au client (tu reçois le montant plein) ;
- `DEDUCTED` : le client est débité exactement du montant demandé, les frais sont
  déduits de ce que tu reçois.

⚠️ `fee_charge_mode` n'est pris en compte que si l'option « Autoriser le choix du
mode de frais par l'API » est activée sur ton compte, sinon il est ignoré
silencieusement. Sur SolPlay, `create-checkout.js` n'envoie volontairement PAS ce
champ : c'est donc le réglage par défaut du compte qui s'applique (à définir dans
Paramètres → Frais), ce qui évite qu'un client voie un montant différent de celui
affiché dans l'application.
