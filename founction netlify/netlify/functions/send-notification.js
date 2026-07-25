/**
 * Fonction serveur Netlify : envoie réellement les notifications push.
 *
 * Remplace la Cloud Function Firebase (qui exigeait le plan payant Blaze).
 * Netlify héberge déjà ton panel admin, et ses fonctions serveur sont
 * gratuites (125 000 appels/mois inclus, sans carte bancaire requise).
 *
 * Sécurité : n'importe qui pourrait sinon appeler cette URL et spammer tous
 * tes clients. On exige donc un jeton d'identité Firebase valide (idToken),
 * le même que celui généré automatiquement quand TOI tu es connecté sur le
 * panel admin - vérifié ici via le SDK Admin (jamais falsifiable côté
 * client). Personne d'autre que toi ne peut donc déclencher un envoi.
 */

const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)),
    databaseURL: process.env.FIREBASE_DATABASE_URL,
  });
}

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method not allowed' };
  }

  let payload;
  try {
    payload = JSON.parse(event.body || '{}');
  } catch {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: 'JSON invalide' }) };
  }

  const { idToken, target, title, body } = payload;

  if (!idToken) {
    return { statusCode: 401, body: JSON.stringify({ ok: false, error: 'Non authentifié' }) };
  }
  try {
    await admin.auth().verifyIdToken(idToken);
  } catch (e) {
    return { statusCode: 401, body: JSON.stringify({ ok: false, error: 'Session invalide, reconnecte-toi au panel.' }) };
  }

  if (!title || !body || !target) {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: 'Champs manquants' }) };
  }

  const db = admin.database();
  const notifRef = db.ref('notifications').push();
  await notifRef.set({
    target,
    title: String(title).slice(0, 80),
    body: String(body).slice(0, 500),
    status: 'pending',
    createdAt: new Date().toISOString(),
  });

  try {
    let tokens = [];
    let tokenMap = {};

    if (target === 'all') {
      const snap = await db.ref('device_tokens').once('value');
      tokenMap = snap.val() || {};
      tokens = Object.values(tokenMap).map((e) => e && e.token).filter((t) => typeof t === 'string' && t.length > 0);
    } else {
      const snap = await db.ref(`device_tokens/${target}/token`).once('value');
      const token = snap.val();
      if (typeof token === 'string' && token.length > 0) {
        tokens = [token];
        tokenMap = { [target]: { token } };
      }
    }

    if (tokens.length === 0) {
      await notifRef.update({ status: 'error', errorMessage: "Aucun jeton trouvé pour ce destinataire (l'app n'a peut-être jamais été ouverte depuis la mise à jour incluant les notifications)." });
      return { statusCode: 200, body: JSON.stringify({ ok: false, reason: 'Aucun jeton trouvé pour ce destinataire.' }) };
    }

    const message = {
      data: { title: String(title).slice(0, 80), body: String(body).slice(0, 500) },
      tokens,
      android: { priority: 'high' },
    };

    const response = await admin.messaging().sendEachForMulticast(message);

    // Nettoyage des jetons devenus invalides (app désinstallée, etc.), pour
    // ne pas retenter indéfiniment à chaque futur envoi.
    const staleUpdates = {};
    response.responses.forEach((r, i) => {
      const code = r.error && r.error.code;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        const key = Object.keys(tokenMap).find((k) => (tokenMap[k] || {}).token === tokens[i]);
        if (key) staleUpdates[key] = null;
      }
    });
    if (Object.keys(staleUpdates).length > 0) {
      await db.ref('device_tokens').update(staleUpdates);
    }

    await notifRef.update({
      status: 'sent',
      successCount: response.successCount,
      failureCount: response.failureCount,
      sentAt: new Date().toISOString(),
    });

    return {
      statusCode: 200,
      body: JSON.stringify({ ok: true, successCount: response.successCount, failureCount: response.failureCount }),
    };
  } catch (err) {
    await notifRef.update({ status: 'error', errorMessage: String(err && err.message ? err.message : err) });
    return { statusCode: 500, body: JSON.stringify({ ok: false, error: String(err && err.message ? err.message : err) }) };
  }
};
