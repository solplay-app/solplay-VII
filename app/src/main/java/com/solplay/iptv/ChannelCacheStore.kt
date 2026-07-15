package com.solplay.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache disque de la dernière liste de chaînes chargée avec succès pour une
 * playlist donnée.
 *
 * Pourquoi : contrairement à d'autres lecteurs IPTV qui "restent connectés"
 * (ouverture instantanée sur l'accueil tant que le code n'a pas expiré),
 * SolPlay retéléchargeait systématiquement toute la playlist et repassait
 * par l'écran de connexion à CHAQUE lancement de l'app - long et frustrant
 * sur les grosses playlists (10 000+ chaînes). Avec ce cache, l'app peut
 * rouvrir directement sur l'accueil avec les chaînes déjà connues, puis se
 * rafraîchir en arrière-plan sans bloquer l'utilisateur (voir HomeActivity).
 */
object ChannelCacheStore {

    private const val FILE_NAME = "channel_cache.json"

    fun save(context: Context, playlistId: String, channels: List<Channel>) {
        try {
            val array = JSONArray()
            for (c in channels) {
                val o = JSONObject()
                o.put("name", c.name)
                o.put("logoUrl", c.logoUrl ?: JSONObject.NULL)
                o.put("groupTitle", c.groupTitle ?: JSONObject.NULL)
                o.put("streamUrl", c.streamUrl)
                array.put(o)
            }
            val root = JSONObject()
            root.put("playlistId", playlistId)
            root.put("savedAt", System.currentTimeMillis())
            root.put("channels", array)
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // Cache best effort : un échec d'écriture ne doit jamais bloquer le chargement.
        }
    }

    /** Renvoie les chaînes en cache pour cette playlist, ou null si absent/périmé/corrompu. */
    fun load(context: Context, playlistId: String): List<Channel>? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            if (root.optString("playlistId") != playlistId) return null

            val array = root.getJSONArray("channels")
            val result = ArrayList<Channel>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(
                    Channel(
                        name = o.getString("name"),
                        logoUrl = if (o.isNull("logoUrl")) null else o.optString("logoUrl"),
                        groupTitle = if (o.isNull("groupTitle")) null else o.optString("groupTitle"),
                        streamUrl = o.getString("streamUrl")
                    )
                )
            }
            result.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Ancienneté du cache pour cette playlist, en millisecondes (Long.MAX_VALUE si absent). */
    fun ageMillis(context: Context, playlistId: String): Long {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return Long.MAX_VALUE
            val root = JSONObject(file.readText())
            if (root.optString("playlistId") != playlistId) return Long.MAX_VALUE
            System.currentTimeMillis() - root.optLong("savedAt", 0)
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            // Ignoré.
        }
    }
}
