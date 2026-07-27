/**
 * Fonction serveur Netlify : reçoit les webhooks Djèko (Jeko) et met à jour
 * la licence Firebase du client concerné.
 *
 * Deux stratégies d'attribution automatique sont désormais supportées :
 * 1) Référence explicite contenant la clé appareil (ancien flux, conservé)
 * 2) Correspondance par numéro de payeur via `payment_intents/` lorsque le
 *    client a saisi son téléphone dans l'app avant de payer.
 *
 * Si plusieurs appareils sont en concurrence pour le même numéro et le même
 * montant, on N'AUTOMATISE PAS : le paiement reste en attente d'assignation
 * manuelle, pour éviter de créditer le mauvais client.
 */

const crypto = require('crypto');
const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)),
    databaseURL: process.env.FIREBASE_DATABASE_URL,
  });
}

const PLAN_BY_AMOUNT = {
  3000: { days: 30, label: '1 mois' },
  9000: { days: 90, label: '3 mois' },
  18000: { days: 180, label: '6 mois' },
  19000: { days: 365, label: '12 mois' },
};

// Compatibilité :
// - ancien format supposé "SP-XXXXXXXX"
// - format réellement affiché aujourd'hui par l'app : 16 caractères hexadécimaux
const LEGACY_DEVICE_KEY_PATTERN = /SP-[A-HJ-NP-Z2-9]{8}/i;
const HEX_DEVICE_KEY_PATTERN = /\b[A-F0-9]{16}\b/i;

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

    const legacy = text.match(LEGACY_DEVICE_KEY_PATTERN);
    if (legacy) return legacy[0].toUpperCase();

    const hex = text.match(HEX_DEVICE_KEY_PATTERN);
    if (hex) return hex[0].toUpperCase();
  }
  return null;
}

function normalizePhone(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const digits = trimmed.replace(/\D+/g, '');
  if (digits.length < 8) return null;
  return trimmed.startsWith('+') ? `+${digits}` : digits;
}

function parseExecutedAt(executedAt) {
  if (typeof executedAt !== 'string' || !executedAt.trim()) return Date.now();
  const normalized = executedAt.includes('T')
    ? executedAt
    : executedAt.replace(' ', 'T') + 'Z';
  const parsed = Date.parse(normalized);
  return Number.isFinite(parsed) ? parsed : Date.now();
}

function normalizeTransactionType(value) {
  return (value || '').toString().toLowerCase().replace(/[^a-z]/g, '');
}

async function findIntentMatch(db, amountValue, counterpartIdentifier, executedAt) {
  const phoneNormalized = normalizePhone(counterpartIdentifier);
  if (!phoneNormalized) {
    return { phoneNormalized: null, match: null, ambiguous: false, reason: 'telephone_introuvable' };
  }

  const snap = await db.ref('payment_intents')
    .orderByChild('phoneNormalized')
    .equalTo(phoneNormalized)
    .once('value');

  if (!snap.exists()) {
    return { phoneNormalized, match: null, ambiguous: false, reason: 'aucune_intention' };
  }

  const executedAtMs = parseExecutedAt(executedAt);
  const candidates = [];

  snap.forEach((child) => {
    const data = child.val() || {};
    if ((data.status || 'pending') !== 'pending') return;
    if (Number(data.amount) !== Number(amountValue)) return;

    const createdAtMs = typeof data.createdAtServer === 'number'
      ? data.createdAtServer
      : Date.parse(data.createdAt || '');

    if (Number.isFinite(createdAtMs)) {
      const delta = executedAtMs - createdAtMs;
      // Tolérance : intention créée jusqu'à 24h avant le paiement.
      if (delta < -10 * 60 * 1000 || delta > 24 * 60 * 60 * 1000) return;
    }

    const deviceKey = typeof data.deviceKey === 'string' ? data.deviceKey.trim().toUpperCase() : null;
    if (!deviceKey) return;

    candidates.push({
      id: child.key,
      ...data,
      deviceKey,
      createdAtMs: Number.isFinite(createdAtMs) ? createdAtMs : 0,
    });
  });

  if (!candidates.length) {
    return { phoneNormalized, match: null, ambiguous: false, reason: 'aucun_candidat_valide' };
  }

  candidates.sort((a, b) => b.createdAtMs - a.createdAtMs);
  const uniqueDeviceKeys = [...new Set(candidates.map((c) => c.deviceKey))];

  if (uniqueDeviceKeys.length > 1) {
    return { phoneNormalized, match: null, ambiguous: true, reason: 'plusieurs_appareils', candidatesCount: candidates.length };
  }

  return {
    phoneNormalized,
    match: candidates[0],
    ambiguous: false,
    reason: 'ok',
    candidatesCount: candidates.length,
  };
}

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method not allowed' };
  }

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
    paymentRequestId: transactionDetails ? transactionDetails.id || null : null,
    paymentLinkId: transactionDetails ? transactionDetails.paymentLinkId || null : null,
    receivedAt: new Date().toISOString(),
    processed: false,
  });

  const normalizedType = normalizeTransactionType(transactionType);
  const isPaymentLike = normalizedType === 'payment' || normalizedType === 'paymentrequest';

  if (status !== 'success' || !isPaymentLike) {
    return { statusCode: 200, body: JSON.stringify({ ok: true, ignored: true }) };
  }

  const amountValue = amount ? amount.amount : null;
  const plan = PLAN_BY_AMOUNT[amountValue];

  let deviceKey = extractDeviceKey(payload);
  let intentMatch = null;
  let attributionMethod = deviceKey ? 'reference' : null;

  if (!deviceKey && plan) {
    intentMatch = await findIntentMatch(db, amountValue, counterpartIdentifier, executedAt);
    if (intentMatch.match) {
      deviceKey = intentMatch.match.deviceKey;
      attributionMethod = 'phone_intent';
    }
  }

  if (!plan || !deviceKey) {
    await paymentRef.update({
      needsManualAssignment: true,
      unmatchedReason: !plan
        ? 'montant_inconnu'
        : intentMatch?.ambiguous
          ? 'plusieurs_intentions_meme_numero'
          : intentMatch?.reason || 'cle_appareil_introuvable',
      autoMatchPhone: intentMatch?.phoneNormalized || normalizePhone(counterpartIdentifier),
      attributionMethod: attributionMethod,
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
    customerPhone: licenseSnap.child('customerPhone').val() || normalizePhone(counterpartIdentifier) || '',
    lastPaymentMethod: paymentMethod || null,
    lastPaymentAt: executedAt || new Date().toISOString(),
  });

  const paymentUpdate = {
    processed: true,
    creditedDeviceKey: deviceKey,
    attributionMethod,
    matchedIntentId: intentMatch?.match?.id || null,
    autoMatchPhone: intentMatch?.phoneNormalized || normalizePhone(counterpartIdentifier),
    needsManualAssignment: false,
  };
  await paymentRef.update(paymentUpdate);

  if (intentMatch?.match?.id) {
    await db.ref('payment_intents').child(intentMatch.match.id).update({
      status: 'matched',
      matchedPaymentId: txnId,
      matchedDeviceKey: deviceKey,
      matchedAt: new Date().toISOString(),
    });
  }

  return {
    statusCode: 200,
    body: JSON.stringify({
      ok: true,
      deviceKey,
      newExpiresAt,
      attributionMethod,
    }),
  };
};
