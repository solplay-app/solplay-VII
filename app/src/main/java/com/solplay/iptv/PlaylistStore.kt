package com.solplay.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gère la liste des playlists enregistrées localement (SharedPreferences
 * CHIFFRÉ, au format JSON) : ajout, modification, suppression, et suivi de
 * la playlist actuellement "connectée".
 *
 * Les identifiants Xtream (username/password) sont sensibles : ce fichier
 * est chiffré via Jetpack Security (EncryptedSharedPreferences) au lieu
 * d'un SharedPreferences en clair. Une migration automatique (voir
 * `migrateFromLegacyPrefsIfNeeded`) recopie une seule fois les anciennes
 * données en clair (fichier "solplay_prefs") vers le nouveau stockage
 * chiffré, puis les efface, pour ne rien casser chez les utilisateurs
 * existants.
 */
object PlaylistStore {

    private const val TAG = "PlaylistStore"

    // Ancien fichier, en clair (conservé uniquement pour la migration).
    private const val LEGACY_PREFS = "solplay_prefs"

    // Nouveau fichier, chiffré.
    private const val PREFS = "solplay_prefs_secure"

    private const val KEY_PLAYLISTS = "saved_playlists"
    private const val KEY_ACTIVE_ID = "active_playlist_id"

    fun getAll(context: Context): List<SavedPlaylist> {
        val raw = prefs(context).getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ajoute la playlist, ou la remplace si une playlist avec le même id existe déjà. */
    fun save(context: Context, playlist: SavedPlaylist) {
        val list = getAll(context).toMutableList()
        val index = list.indexOfFirst { it.id == playlist.id }
        if (index >= 0) list[index] = playlist else list.add(playlist)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).filterNot { it.id == id }
        persist(context, list)
        if (getActiveId(context) == id) setActiveId(context, null)
    }

    fun getActiveId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_ID, null)

    fun setActiveId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    private fun persist(context: Context, list: List<SavedPlaylist>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    private fun toJson(p: SavedPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("mode", p.mode.name)
        put("m3uUrl", p.m3uUrl)
        put("xtreamServer", p.xtreamServer)
        put("xtreamUsername", p.xtreamUsername)
        put("xtreamPassword", p.xtreamPassword)
        put("fromCode", p.fromCode ?: "")
    }

    private fun fromJson(o: JSONObject): SavedPlaylist = SavedPlaylist(
        id = o.getString("id"),
        name = o.getString("name"),
        mode = PlaylistMode.valueOf(o.getString("mode")),
        m3uUrl = o.optString("m3uUrl", ""),
        xtreamServer = o.optString("xtreamServer", ""),
        xtreamUsername = o.optString("xtreamUsername", ""),
        xtreamPassword = o.optString("xtreamPassword", ""),
        fromCode = o.optString("fromCode", "").ifEmpty { null }
    )

    private fun prefs(context: Context): SharedPreferences {
        val secure = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Si le chiffrement échoue pour une raison quelconque (device
            // très ancien, Keystore indisponible...), on retombe sur un
            // SharedPreferences classique plutôt que de planter l'app :
            // mieux vaut des identifiants en clair qu'une app qui ne
            // charge plus aucune playlist.
            Log.e(TAG, "Échec de l'ouverture du stockage chiffré, repli sur stockage non chiffré", e)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        migrateFromLegacyPrefsIfNeeded(context, secure)
        return secure
    }

    /**
     * Recopie une seule fois les données de l'ancien SharedPreferences en
     * clair vers le nouveau stockage chiffré, puis supprime l'ancien
     * fichier. Ne fait rien si la migration a déjà eu lieu ou si aucune
     * ancienne donnée n'existe (nouvelle installation).
     */
    private fun migrateFromLegacyPrefsIfNeeded(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyPlaylists = legacy.getString(KEY_PLAYLISTS, null) ?: return // rien à migrer

        try {
            val editor = secure.edit()
            editor.putString(KEY_PLAYLISTS, legacyPlaylists)
            legacy.getString(KEY_ACTIVE_ID, null)?.let { editor.putString(KEY_ACTIVE_ID, it) }
            editor.apply()

            // On efface l'ancien fichier en clair pour que les identifiants
            // n'y restent pas dupliqués sur le disque.
            legacy.edit().clear().apply()
            Log.i(TAG, "Migration des playlists vers le stockage chiffré terminée")
        } catch (e: Exception) {
            Log.e(TAG, "Échec de la migration vers le stockage chiffré, anciennes données conservées", e)
        }
    }
}
