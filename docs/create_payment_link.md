> ## Documentation Index
> Fetch the complete documentation index at: https://developer.jeko.africa/llms.txt
> Use this file to discover all available pages before exploring further.

# Créer un lien de paiement

> Crée un nouveau lien de paiement pour l'entreprise authentifiée.

**Fonctionnalités :**
- URL partageable : Retourne un lien qui peut être partagé avec les clients
- Contrôle des paiements : Configurez des liens à usage unique ou réutilisables via `allowMultiplePayments`
- Statut en temps réel : La réponse inclut `canReceivePayments` pour vérifier la disponibilité du lien

**Champs de réponse :**
- `canReceivePayments` : Toujours `true` pour les liens nouvellement créés
- `allowMultiplePayments` : Contrôle si le lien peut accepter plusieurs paiements
- `link` : URL partageable à envoyer aux clients




## OpenAPI

````yaml /fr/api-reference/openapi.yml post /partner_api/payment_links
openapi: 3.1.0
info:
  title: Jeko Partner API
  version: 1.0.0
  license:
    name: Jeko Inc.
    url: jeko.africa
  termsOfService: https://jeko.africa
  contact:
    name: Jeko team
    url: https://jeko.africa
    email: development@jeko.africa
  description: >
    API Partenaire pour les intégrations tierces authentifiées via des clés API.


    ## Cas d'usage d'intégration


    **L'API Partenaire Jeko prend en charge trois modèles d'intégration
    principaux pour s'adapter aux différents besoins commerciaux et exigences
    techniques.**


    ### 1. Intégration de paiement en magasin

    **Parfait pour les emplacements de magasins physiques avec des dispositifs
    soundbox Jeko.**


    **Flux :**

    1. Obtenez vos identifiants API depuis [Jeko
    Cockpit](https://cockpit.jeko.africa/)

    2. Configurez le webhook pour la réconciliation

    3. Trouvez votre ID de magasin et localisez les dispositifs soundbox

    4. Créez une demande de paiement avec `type: "soundbox"` en utilisant
    `storeId` et `deviceId`

    5. Le QR code apparaît sur le dispositif soundbox Jeko

    6. Le client scanne le QR code avec son application de paiement mobile

    7. Recevez une notification webhook pour la réconciliation


    **Cas d'usage :** Click & Collect, Commandes en magasin, Commerce hybride


    ### 2. Intégration de paiement e-commerce

    **Intégration légère pour les boutiques en ligne utilisant des liens de
    paiement.**


    **Flux :**

    1. Obtenez vos identifiants API depuis [Jeko
    Cockpit](https://cockpit.jeko.africa/)

    2. Configurez le webhook pour la réconciliation

    3. Trouvez votre ID de magasin

    4. Créez un lien de paiement en utilisant `storeId` et enregistrez l'ID de
    lien de paiement retourné

    5. Redirigez les clients vers l'URL du lien de paiement

    6. Recevez une notification webhook pour la réconciliation


    **Comportement des liens de paiement :**

    - **Par défaut** : Liens de paiement à usage unique (`allowMultiplePayments:
    false`)
      - Le lien devient indisponible une fois qu'un paiement est complété
    - **Réutilisable** : Plusieurs paiements autorisés (`allowMultiplePayments:
    true`)
      - Le lien reste actif pour plusieurs transactions
      - Aucune restriction sur les paiements simultanés ou séquentiels

    **Validation des liens de paiement :**

    - Utilisez le champ `canReceivePayments` pour vérifier si un lien peut
    accepter de nouveaux paiements

    - Retourne `true` lorsque :
      - Le lien autorise plusieurs paiements, OU
      - Il n'y a pas de demandes de paiement complétées
    - Les tentatives de paiement sur des liens indisponibles retournent le code
    d'erreur `E_PAYMENT_LINK_IS_INVALID`


    **Cas d'usage :** Paiement en ligne, Services d'abonnement, Dons, Paiements
    d'applications mobiles


    ### 3. Intégration de paiement dans l'application

    **Intégration transparente pour les applications mobiles utilisant des
    demandes de paiement avec flux de redirection.**


    **Flux :**

    1. Obtenez vos identifiants API depuis [Jeko
    Cockpit](https://cockpit.jeko.africa/)

    2. Configurez le webhook pour la réconciliation

    3. Trouvez votre ID de magasin

    4. Créez une demande de paiement avec `type: "redirect"` et configurez les
    URLs de succès/erreur

    5. Les utilisateurs sont redirigés vers leur application de paiement mobile
    (Wave, Orange Money, Djamo, etc.)

    6. Les utilisateurs complètent le paiement dans leur application de paiement
    familière

    7. Les utilisateurs sont redirigés vers votre application via les URLs de
    succès/erreur

    8. Recevez une notification webhook pour la réconciliation


    **Mode direct optionnel :** Lorsque vous connaissez déjà le numéro du payeur
    et la méthode de paiement, définissez
    `paymentDetails.data.forceProviderDirect` à `true` pour utiliser un flux de
    paiement direct. Lorsque ce drapeau est activé,
    `paymentDetails.data.payerPhone` est requis.


    **Applications de paiement supportées :** Wave, Orange Money, Djamo, MTN,
    Moov


    **Cas d'usage :** E-commerce mobile, Applications de livraison de
    nourriture, Applications de transport, Applications de jeux, Applications de
    services


    ### Avantages de l'intégration

    - **Architecture flexible** : Choisissez le modèle d'intégration qui
    correspond à votre modèle commercial

    - **Plusieurs méthodes de paiement** : Support de tous les principaux
    fournisseurs de paiement d'Afrique de l'Ouest

    - **Traitement sécurisé** : Toutes les données de paiement sensibles gérées
    par l'infrastructure sécurisée de Jeko

    - **Notifications en temps réel** : Notifications webhook instantanées pour
    la complétion des paiements

    - **Réconciliation facile** : Détails complets des transactions pour un
    suivi précis des paiements

    - **Intégration minimale** : APIs légères nécessitant des modifications
    minimales côté serveur


    ## URL de base


    **Environnement de production :**

    - URL de base : `https://api.jeko.africa`

    - Description : Serveur de production (utilise des données en direct)

    - Statut : Actif

    - HTTPS requis : Tous les appels API doivent utiliser HTTPS


    **Points de terminaison API :**

    Tous les points de terminaison API sont préfixés avec l'URL de base. Par
    exemple :

    - Magasins : `GET https://api.jeko.africa/partner_api/stores`

    - Appareils : `GET https://api.jeko.africa/partner_api/devices`

    - Demandes de paiement : `POST
    https://api.jeko.africa/partner_api/payment_requests`

    - Liens de paiement : `POST
    https://api.jeko.africa/partner_api/payment_links`


    ## Authentification


    Toutes les requêtes API nécessitent une authentification à l'aide de clés
    API. Vous devez inclure deux en-têtes avec chaque requête :

    - `X-API-KEY` : Votre clé API

    - `X-API-KEY-ID` : Votre ID de clé API


    ### Obtenir les clés API


    Pour obtenir votre clé API et votre ID de clé API :

    1. Connectez-vous au Jeko Cockpit sur
    [https://cockpit.jeko.africa/](https://cockpit.jeko.africa/)

    2. Naviguez vers **Paramètres** > **API & Webhooks**

    3. Générez ou copiez vos identifiants API depuis cette section


    ### Bonnes pratiques de sécurité

    - Gardez vos clés API sécurisées et ne les exposez jamais dans le code côté
    client

    - Les clés API fournissent un accès complet à votre compte d'entreprise

    - Faites tourner vos clés régulièrement pour une sécurité renforcée

    - Utilisez HTTPS pour toutes les requêtes API


    ### Exemple de requête

    ```bash

    curl -X GET "https://api.jeko.africa/partner_api/stores" \
      -H "X-API-KEY: your_api_key_here" \
      -H "X-API-KEY-ID: your_api_key_id_here"
    ```


    ### Erreurs d'authentification

    - `401 Unauthorized` : Clé API invalide ou manquante

    - `403 Forbidden` : La clé API n'a pas la permission pour la ressource
    demandée


    ## Gestion des magasins et des appareils


    ### Contexte du magasin

    - Les entreprises peuvent avoir plusieurs magasins (emplacements physiques,
    boutiques en ligne, etc.)

    - Toutes les opérations de paiement nécessitent un ID de magasin spécifique

    - Récupérez toujours les magasins disponibles en premier en utilisant le
    point de terminaison des magasins

    - Les IDs de magasin sont obligatoires pour créer des demandes de paiement
    et des liens de paiement


    ### Gestion des appareils

    - Les appareils sont des terminaux QR soundbox physiques qui peuvent traiter
    les paiements

    - Chaque appareil est associé à un magasin spécifique

    - Les IDs d'appareil sont requis pour créer des demandes de paiement
    soundbox

    - Utilisez le point de terminaison des appareils pour récupérer les
    appareils disponibles pour votre entreprise


    ## Dépannage


    ### Problèmes courants


    **Aucun appareil retourné**

    - Assurez-vous que votre entreprise a des dispositifs soundbox configurés

    - Vérifiez que les appareils sont associés à des magasins qui appartiennent
    à votre entreprise

    - Vérifiez que les appareils sont de type "SoundBoxQrTerminal"


    **Erreur Appareil non trouvé**

    - Utilisez toujours les IDs d'appareil du point de terminaison
    `/partner_api/devices`

    - Les IDs d'appareil sont sensibles à la casse

    - Assurez-vous que l'appareil appartient au magasin spécifié dans votre
    demande de paiement


    **Erreur Magasin non trouvé**

    - Utilisez les IDs de magasin du point de terminaison `/partner_api/stores`

    - Les IDs de magasin doivent être des UUID valides

    - Assurez-vous que le magasin appartient à votre entreprise authentifiée


    **Erreurs de validation des demandes de paiement**

    - Pour les paiements soundbox : `deviceId` est requis

    - Pour les paiements redirect : `successUrl` et `errorUrl` sont requis

    - Toutes les demandes de paiement nécessitent un `storeId` valide

    - `amountCents` doit être d'au moins 100 (1 XOF)


    **Erreurs de validation des liens de paiement**

    - `E_PAYMENT_LINK_IS_INVALID` : Le lien de paiement ne peut pas accepter de
    nouveaux paiements
      - Se produit lorsqu'un lien à usage unique (`allowMultiplePayments: false`) a un paiement complété
      - Vérifiez le champ `canReceivePayments` avant de diriger les clients vers le lien

    ### Format de réponse d'erreur

    Toutes les réponses d'erreur suivent ce format :

    ```json

    {
      "id": "identifiant_erreur",
      "message": "Message d'erreur lisible par l'humain",
      "extras": "Détails d'erreur supplémentaires"
    }

    ```


    ## Notifications Webhook


    Lorsque les demandes de paiement sont complétées, des notifications webhook
    sont envoyées à votre point de terminaison configuré.


    ### Authentification Webhook

    - Vérifiez l'authenticité du webhook en utilisant l'en-tête `Jeko-Signature`

    - La signature est générée en utilisant HMAC-SHA256 avec votre secret
    webhook


    ### Charge utile Webhook

    La charge utile du webhook inclut :

    - Détails de transaction standard (montant, frais, statut, etc.)

    - Objet `transactionDetails` contenant votre ID de demande de paiement et
    référence

    - ID de lien de paiement (lorsque le paiement a été effectué via un lien de
    paiement)

    - Cela vous permet de corréler les notifications webhook avec vos demandes
    de paiement et liens de paiement originaux


    ### Politique de nouvelle tentative Webhook

    - Les webhooks sont réessayés jusqu'à 3 fois avec un backoff exponentiel

    - Assurez-vous que votre point de terminaison retourne des codes de statut
    HTTP 2xx pour un traitement réussi


    ### Sécurité Webhook

    - Vérifiez toujours la signature avant de traiter les charges utiles webhook

    - Utilisez des points de terminaison HTTPS pour les URLs webhook


    ### Schéma Webhook


    La charge utile du webhook suit cette structure lorsqu'une transaction est
    complétée :


    ```json

    {
      "id": "string",                    // Identifiant de transaction (obligatoire)
      "amount": {                        // Montant de la transaction (obligatoire)
        "amount": "number",              // Montant en centimes (obligatoire)
        "currency": "string"             // Code devise (ISO 4217) (obligatoire)
      },
      "fees": {                         // Frais de transaction (obligatoire)
        "amount": "number",              // Montant des frais en centimes (obligatoire)
        "currency": "string"             // Code devise (ISO 4217) (obligatoire)
      },
      "status": "string",               // Statut de transaction : "success", "pending", "error" (obligatoire)
      "counterpartLabel": "string",     // Nom d'affichage du client (obligatoire)
      "counterpartIdentifier": "string", // Identifiant du client (téléphone, ID d'appareil, etc.) (obligatoire)
      "paymentMethod": "string",        // Méthode de paiement : "orange", "wave", "mtn", "moov", "djamo" (obligatoire)
      "transactionType": "string",      // Type de transaction : "PaymentRequest" (obligatoire)
      "businessName": "string",         // Nom de l'entreprise (obligatoire)
      "storeName": "string",            // Nom du magasin (obligatoire)
      "description": "string",          // Description de la transaction (obligatoire)
      "executedAt": "string",           // Horodatage d'exécution (YYYY-MM-DD HH:mm:ss) (obligatoire)
      "transactionDetails": {           // Détails spécifiques à l'API (obligatoire)
        "id": "string",                 // ID de demande de paiement (optionnel - présent uniquement lorsque la demande de paiement a été créée via l'API Partenaire)
        "reference": "string",          // Référence de demande de paiement (optionnel - présent uniquement lorsque la demande de paiement a été créée via l'API Partenaire)
        "paymentLinkId": "string"       // ID de lien de paiement (optionnel - présent uniquement lorsque le paiement a été effectué via un lien de paiement)
      }
    }

    ```


    ### Exemples Webhook


    **Paiement Redirect Complété :**

    ```json

    {
      "id": "txn_1234567890",
      "amount": {
        "amount": 10000,
        "currency": "XOF"
      },
      "fees": {
        "amount": 150,
        "currency": "XOF"
      },
      "status": "success",
      "counterpartLabel": "Nom du client",
      "counterpartIdentifier": "+2250701234567",
      "paymentMethod": "wave",
      "transactionType": "PaymentRequest",
      "businessName": "Entreprise Partenaire",
      "storeName": "Magasin Partenaire",
      "description": "Paiement pour commande #12345",
      "executedAt": "2024-01-15 14:30:25",
      "transactionDetails": {
        "id": "pr_abc123def456",
        "reference": "PAY-2024-001"
      }
    }

    ```


    **Paiement Soundbox Complété :**

    ```json

    {
      "id": "txn_0987654321",
      "amount": {
        "amount": 5000,
        "currency": "XOF"
      },
      "fees": {
        "amount": 75,
        "currency": "XOF"
      },
      "status": "success",
      "counterpartLabel": "Client Appareil",
      "counterpartIdentifier": "DEVICE-123",
      "paymentMethod": "orange",
      "transactionType": "PaymentRequest",
      "businessName": "Entreprise Partenaire",
      "storeName": "Magasin Partenaire",
      "description": "Paiement soundbox",
      "executedAt": "2024-01-15 15:45:10",
      "transactionDetails": {
        "id": "pr_xyz789uvw012",
        "reference": "PAY-2024-002"
      }
    }

    ```


    **Lien de Paiement Complété :**

    ```json

    {
      "id": "txn_5555666777",
      "amount": {
        "amount": 15000,
        "currency": "XOF"
      },
      "fees": {
        "amount": 225,
        "currency": "XOF"
      },
      "status": "success",
      "counterpartLabel": "Client Lien",
      "counterpartIdentifier": "+2250701234567",
      "paymentMethod": "wave",
      "transactionType": "PaymentRequest",
      "businessName": "Entreprise Partenaire",
      "storeName": "Magasin Partenaire",
      "description": "Paiement via lien de paiement",
      "executedAt": "2024-01-15 16:20:30",
      "transactionDetails": {
        "paymentLinkId": "pl_def456ghi789"
      }
    }

    ```


    Pour les détails d'intégration complets, voir :
    [https://www.notion.so/Jeko-Webhook-Integration-Guide-258d6161e31781aba608efbe4a025463](https://www.notion.so/Jeko-Webhook-Integration-Guide-258d6161e31781aba608efbe4a025463)
servers:
  - url: https://api.jeko.africa
    description: Serveur de production (utilise des données en direct)
security: []
tags:
  - name: Magasins
    description: >-
      Points de terminaison de gestion et d'information des magasins. Chez Jeko,
      les entreprises peuvent avoir plusieurs magasins, et toutes les opérations
      de paiement sont effectuées dans le contexte d'un magasin spécifique.
  - name: Appareils
    description: >-
      Points de terminaison de gestion et d'information des appareils. Les
      appareils sont des terminaux QR soundbox physiques qui peuvent traiter les
      paiements.
  - name: Liens de paiement
    description: Création et gestion des liens de paiement
  - name: Demandes de paiement
    description: Création et gestion des demandes de paiement
  - name: Contacts
    description: >-
      Gestion des contacts bénéficiaires pour les virements. Les contacts
      peuvent être des comptes mobile money ou des comptes bancaires.
  - name: Virements
    description: >-
      Virement de fonds depuis les portefeuilles de magasin vers les contacts
      bénéficiaires
  - name: Banques
    description: Liste des banques prises en charge pour les virements bancaires
  - name: Transactions
    description: Liste des transactions
  - name: Localisations
    description: Liste des villes et municipalités pour l'enregistrement des entreprises
  - name: Revenus estimés
    description: >-
      Liste des fourchettes de revenus estimés pour l'enregistrement des
      entreprises
  - name: Activités
    description: Liste des catégories d'activités commerciales et des activités
  - name: Fournisseurs de services
    description: >-
      Points de terminaison pour les fournisseurs de services pour l'intégration
      d'entreprises et la gestion des clés API
paths:
  /partner_api/payment_links:
    post:
      tags:
        - Liens de paiement
      summary: Créer un lien de paiement
      description: >
        Crée un nouveau lien de paiement pour l'entreprise authentifiée.


        **Fonctionnalités :**

        - URL partageable : Retourne un lien qui peut être partagé avec les
        clients

        - Contrôle des paiements : Configurez des liens à usage unique ou
        réutilisables via `allowMultiplePayments`

        - Statut en temps réel : La réponse inclut `canReceivePayments` pour
        vérifier la disponibilité du lien


        **Champs de réponse :**

        - `canReceivePayments` : Toujours `true` pour les liens nouvellement
        créés

        - `allowMultiplePayments` : Contrôle si le lien peut accepter plusieurs
        paiements

        - `link` : URL partageable à envoyer aux clients
      operationId: partner_createPaymentLink
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePaymentLinkRequest'
      responses:
        '200':
          description: Lien de paiement créé avec succès
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PaymentLink'
        '400':
          description: Requête incorrecte - données d'entrée invalides
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '401':
          description: Clé API invalide ou manquante
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UnauthorizedResponse'
        '403':
          description: Accès interdit
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ForbiddenActionErrorResponse'
        '404':
          description: Magasin non trouvé
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '422':
          description: Erreur de validation - données d'entrée invalides
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ValidationErrorResponse'
        '500':
          description: Une erreur s'est produite
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
      security:
        - partnerApiKey: []
        - partnerApiKeyId: []
components:
  schemas:
    CreatePaymentLinkRequest:
      type: object
      properties:
        storeId:
          type: string
          description: Identifiant du magasin
          example: 59ae202a-f583-4a15-970f-9e99bd1e0baa
          format: uuid
        title:
          type: string
          description: Payment link title
          example: Payment for services
          minLength: 10
          maxLength: 255
        amountCents:
          type: integer
          description: Fixed amount in cents
          example: 10000
          minimum: 10000
        currency:
          type: string
          description: Code devise (ISO 4217)
          example: XOF
          minLength: 3
          maxLength: 3
          default: XOF
        allowMultiplePayments:
          type: boolean
          description: Whether the payment link allows multiple payments
          example: false
          default: false
      required:
        - storeId
        - title
        - amountCents
        - currency
    PaymentLink:
      type: object
      description: Response schema for payment link operations
      properties:
        id:
          type: string
          description: Payment link identifier
          example: d22c81f3-ee04-4ec5-8bd2-cd8af5dabcfc
        storeId:
          type: string
          description: Identifiant du magasin
          example: 59ae202a-f583-4a15-970f-9e99bd1e0baa
        title:
          type: string
          description: Payment link title
          example: Payment for services
        amount:
          allOf:
            - $ref: '#/components/schemas/MoneyModel'
            - description: Fixed amount in cents
          example:
            amount: 10000
            currency: XOF
        allowMultiplePayments:
          type: boolean
          description: >
            Whether the payment link allows multiple payments.

            - `false`: One-time use link (becomes unavailable once payment is
            completed)

            - `true`: Reusable link (can accept multiple payments)
          example: false
        canReceivePayments:
          type: boolean
          description: >
            Indicates whether the payment link can currently accept new
            payments.

            Returns `true` when:

            - The link allows multiple payments (`allowMultiplePayments: true`),
            OR

            - There are no completed payment requests associated with this link


            **Important:** Always check this field before directing customers to
            the payment link.

            Attempting to pay through a link with `canReceivePayments: false`
            will result in an error.
          example: true
        link:
          type: string
          description: Shareable payment link URL
          example: https://pay.jeko.africa/c/abc123def456
          format: uri
      required:
        - id
        - storeId
        - title
        - amount
        - allowMultiplePayments
        - canReceivePayments
        - link
    ErrorResponse:
      type: object
      properties:
        id:
          type: string
          example: internal_error
        message:
          type: string
          example: Une erreur inattendue s'est produite
        extras:
          type: string
          example: Détails supplémentaires de l'erreur
    UnauthorizedResponse:
      type: object
      properties:
        id:
          type: string
          example: unauthorized
        message:
          type: string
          example: Unauthorized
        extras:
          type: string
          example: Détails supplémentaires de l'erreur
    ForbiddenActionErrorResponse:
      type: object
      properties:
        id:
          type: string
          example: forbidden_action
        message:
          type: string
          example: Action interdite
        extras:
          type: string
          example: Détails supplémentaires de l'erreur
    ValidationErrorResponse:
      type: object
      properties:
        id:
          type: string
          example: validation_error
        message:
          type: string
          example: Échec de la validation
        extras:
          type: object
          properties:
            errors:
              type: array
              items:
                type: object
                properties:
                  field:
                    type: string
                    example: paymentDetails.data.paymentMethod
                  rule:
                    type: string
                    example: in
                  message:
                    type: string
                    example: >-
                      The paymentDetails.data.paymentMethod must be one of the
                      following: orange, wave, mtn, moov, djamo
              example:
                - field: paymentDetails.data.paymentMethod
                  rule: in
                  message: >-
                    The paymentDetails.data.paymentMethod must be one of the
                    following: orange, wave, mtn, moov, djamo
                - field: paymentDetails.data.deviceId
                  rule: required
                  message: >-
                    The paymentDetails.data.deviceId field is required when
                    paymentDetails.type is soundbox
    MoneyModel:
      type: object
      description: Money amount representation
      properties:
        amount:
          type: integer
          description: Amount as string (to avoid precision issues)
          example: 10000
        currency:
          type: string
          description: Code devise (ISO 4217)
          example: XOF
      required:
        - amount
        - currency
  securitySchemes:
    partnerApiKey:
      type: apiKey
      in: header
      name: X-API-KEY
      description: Clé API pour les requêtes de l'API Partenaire
    partnerApiKeyId:
      type: apiKey
      in: header
      name: X-API-KEY-ID
      description: ID de clé API pour les requêtes de l'API Partenaire

````