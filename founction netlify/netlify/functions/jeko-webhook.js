/**
 * Fonction serveur Netlify : reçoit les webhooks Djèko (Jeko) et met à
 * jour la licence Firebase du client concerné.
 *
 * Remplace l'ancienne intégration Zayono, entièrement retirée.
 *
 * ─────────────────────────────────────────────────────────────────────
 * IMPORTANT — ce qui est certain vs ce qui est une hypothèse :
 *
 * CERTAIN (documenté dans "Intégration des Webhooks" fourni par
 * l'utilisateur) :
 *   - événement unique "transaction.completed"
 *   - vérification de signature HMAC-SHA256 via l'en-tête "Jeko-Signature"
 *   - structure exacte du payload JSON (id, amount, status, paymentMethod,
 *     transactionType, transactionDetails.reference, etc.)
 *   - doit répondre HTTP 200 sous 5 secondes
 *
 * HYPOTHÈSE (la doc fournie couvre uniquement la RÉCEPTION des paiements,
 * pas la création d'un paiement dynamique par l'app - aucune API "créer un
 * paiement" n'a été documentée) :
 *   - Le client paie via un lien de paiement statique créé à l'avance dans
 *     le Cockpit Djèko pour chaque forfait (voir SubscriptionPlan.kt côté
 *     app), PAS via un appel API dynamique.
 *   - Comme ce lien est le même pour tous les clients d'un même forfait,
 *     le webhook seul ne sait pas QUEL appareil créditer. Pour résoudre
 *     ça : l'app affiche la clé appareil du client et lui demande de la
 *     coller dans le champ "Référence"/"Note" au moment de payer (si le
 *     paiement Djèko le permet). Ce webhook cherche alors ce motif
 *     "SP-XXXXXXXX" dans transactionDetails.reference PUIS description.
 *   - Si la clé n'est pas trouvée automatiquement (client qui a oublié de
 *     la renseigner, ou champ non disponible côté Djèko) : le paiement est
 *     simplement enregistré dans `pending_payments/{id}` sur Firebase,
 *     pour assignation manuelle par l'admin (le panneau admin a déjà un
 *     flux "Assigner" rapide - il suffira d'y ajouter un onglet "Paiements
 *     en attente" listant ces entrées, si tu veux que je le fasse ensuite).
 * ─────────────────────────────────────────────────────────────────────
 */

const crypto = require('crypto');
const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)),
    databaseURL: process.env.FIREBASE_DATABASE_URL,
  });
}

// Montant exact (XOF) -> durée du forfait en jours. Doit rester synchronisé
// avec SubscriptionPlan.kt côté app (1 mois/3000, 3 mois/9000, 6 mois/18000,
// 12 mois/19000 FCFA).
const PLAN_BY_AMOUNT = {
  3000: { days: 30, label: '1 mois' },
  9000: { days: 90, label: '3 mois' },
  18000: { days: 180, label: '6 mois' },
  19000: { days: 365, label: '12 mois' },
};

// Format généré côté panneau admin (LicenseEditActivity.generateKey()) :
// "SP-" + 8 caractères parmi ABCDEFGHJKLMNPQRSTUVWXYZ23456789.
const DEVICE_KEY_PATTERN = /SP-[A-HJ-NP-Z2-9]{8}/i;

function verifySignature(rawBody, signatureHeader, secret) {
  if (!signatureHeader || !secret) return false;
  const provided = signatureHeader.startsWith('sha256=')
    ? signatureHeader.slice('sha256='.length)
    : signatureHeader;

  const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex');

  const a = Buffer.from(provided, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function extractDeviceKey(payload) {
  const haystacks = [
    payload.transactionDetails && payload.transactionDetails.reference,
    payload.description,
  ];
  for (const text of haystacks) {
    if (typeof text !== 'string') continue;
    const match = text.match(DEVICE_KEY_PATTERN);
    if (match) return match[0].toUpperCase();
  }
  return null;
}

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method not allowed' };
  }

  // Corps BRUT requis pour la vérification HMAC (surtout pas JSON.parse
  // avant, la doc est explicite là-dessus : "raw body, pas le JSON analysé").
  const rawBody = event.isBase64Encoded
    ? Buffer.from(event.body || '', 'base64').toString('utf8')
    : (event.body || '');

  const signatureHeader = event.headers['jeko-signature'] || event.headers['Jeko-Signature'];
  const secret = process.env.JEKO_WEBHOOK_SECRET;

  if (!verifySignature(rawBody, signatureHeader, secret)) {
    return { statusCode: 401, body: JSON.stringify({ ok: false, error: 'Signature invalide' }) };
  }

  let payload;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: 'JSON invalide' }) };
  }

  const {
    id: txnId,
    amount,
    status,
    transactionType,
    counterpartLabel,
    counterpartIdentifier,
    paymentMethod,
    description,
    executedAt,
    transactionDetails,
  } = payload;

  if (!txnId) {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: 'Transaction sans id' }) };
  }

  const db = admin.database();
  const paymentRef = db.ref('payments').child(txnId);

  // Idempotence : Djèko peut retenter l'envoi si la réponse précédente a
  // été trop lente ou perdue. On ne crédite jamais deux fois.
  const existing = await paymentRef.once('value');
  if (existing.exists() && existing.child('processed').val() === true) {
    return { statusCode: 200, body: JSON.stringify({ ok: true, alreadyProcessed: true }) };
  }

  await paymentRef.set({
    amount: amount ? amount.amount : null,
    currency: amount ? amount.currency : null,
    status: status || null,
    transactionType: transactionType || null,
    paymentMethod: paymentMethod || null,
    counterpartLabel: counterpartLabel || null,
    counterpartIdentifier: counterpartIdentifier || null,
    description: description || null,
    executedAt: executedAt || null,
    reference: transactionDetails ? transactionDetails.reference || null : null,
    receivedAt: new Date().toISOString(),
    processed: false,
  });

  // Seuls les paiements entrants réussis débloquent une licence. Les
  // transferts (transactionType "transfer") ne concernent pas les
  // abonnements clients et ne sont jamais traités ici.
  if (status !== 'success' || transactionType !== 'payment') {
    return { statusCode: 200, body: JSON.stringify({ ok: true, ignored: true }) };
  }

  const amountValue = amount ? amount.amount : null;
  const plan = PLAN_BY_AMOUNT[amountValue];
  const deviceKey = extractDeviceKey(payload);

  if (!plan || !deviceKey) {
    // Montant non reconnu et/ou clé appareil introuvable dans la
    // référence/description : on laisse la trace en base pour une
    // assignation manuelle côté admin, mais on répond quand même 200 -
    // c'est un cas attendu, pas une erreur d'intégration.
    await paymentRef.update({
      needsManualAssignment: true,
      unmatchedReason: !plan ? 'montant_inconnu' : 'cle_appareil_introuvable',
    });
    return { statusCode: 200, body: JSON.stringify({ ok: true, needsManualAssignment: true }) };
  }

  const licenseRef = db.ref('licenses').child(deviceKey);
  const licenseSnap = await licenseRef.once('value');
  const now = Date.now();
  const currentExpiresAt = licenseSnap.child('expiresAt').val();
  const base = typeof currentExpiresAt === 'number' && currentExpiresAt > now ? currentExpiresAt : now;
  const newExpiresAt = base + plan.days * 24 * 60 * 60 * 1000;

  await licenseRef.update({
    active: true,
    expiresAt: newExpiresAt,
    planLabel: plan.label,
    customerName: licenseSnap.child('customerName').val() || counterpartLabel || '',
    lastPaymentMethod: paymentMethod || null,
    lastPaymentAt: executedAt || new Date().toISOString(),
  });

  await paymentRef.update({ processed: true, creditedDeviceKey: deviceKey });

  return { statusCode: 200, body: JSON.stringify({ ok: true, deviceKey, newExpiresAt }) };
};
