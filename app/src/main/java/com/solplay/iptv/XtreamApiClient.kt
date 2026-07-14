package com.solplay.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Certains panels Xtream ne renseignent pas (ou très partiellement) l'attribut
 * `group-title` dans le fichier M3U (`get.php?...&type=m3u_plus`) pour les
 * entrées Films/Séries, alors que ce même panel connaît parfaitement la
 * catégorie de chaque contenu via son API JSON native (`player_api.php`).
 * Résultat concret côté app : la colonne "bouquets" reste vide à part
 * "Tous", puisqu'on ne peut grouper que sur un champ qui n'existe pas.
 *
 * Ce client interroge cette API JSON (get_vod_categories/get_vod_streams et
 * get_live_categories/get_live_streams) pour reconstituer une correspondance
 * fiable stream_id -> nom de catégorie, qu'on utilise ensuite pour compléter
 * (jamais écraser une valeur déjà présente et non vide) le group-title
 * manquant des chaînes obtenues via le M3U.
 *
 * Best effort : toute erreur réseau/JSON est avalée silencieusement (retourne
 * une map vide) pour ne jamais empêcher le chargement de la playlist -
 * seule la catégorisation en pâtit, pas la lecture des chaînes.
 */
object XtreamApiClient {

    private const val TAG = "XtreamApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** stream_id (VOD ou Live, selon [kind]) -> nom de catégorie. */
    suspend fun fetchCategoryMap(playlist: SavedPlaylist, kind: Kind): Map<Int, String> {
        if (playlist.mode != PlaylistMode.XTREAM) return emptyMap()

        return withContext(Dispatchers.IO) {
            try {
                val base = playlist.xtreamServer.trim().trimEnd('/')
                val user = playlist.xtreamUsername.trim()
                val pass = playlist.xtreamPassword.trim()
                if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) return@withContext emptyMap()

                val categories = fetchJsonArray(
                    "$base/player_api.php?username=$user&password=$pass&action=${kind.categoriesAction}"
                ) ?: return@withContext emptyMap()

                val categoryNames = mutableMapOf<String, String>()
                for (i in 0 until categories.length()) {
                    val c = categories.optJSONObject(i) ?: continue
                    val id = c.optString("category_id")
                    val name = c.optString("category_name")
                    if (id.isNotEmpty() && name.isNotEmpty()) categoryNames[id] = name
                }
                if (categoryNames.isEmpty()) return@withContext emptyMap()

                val streams = fetchJsonArray(
                    "$base/player_api.php?username=$user&password=$pass&action=${kind.streamsAction}"
                ) ?: return@withContext emptyMap()

                val result = mutableMapOf<Int, String>()
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val streamId = s.optInt("stream_id", -1)
                    val categoryId = s.optString("category_id")
                    val categoryName = categoryNames[categoryId]
                    if (streamId > 0 && categoryName != null) {
                        result[streamId] = categoryName
                    }
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "Échec récupération des catégories Xtream (${kind.name}) : ${e.message}")
                emptyMap()
            }
        }
    }

    private fun fetchJsonArray(url: String): JSONArray? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} pour $url")
                return null
            }
            val body = response.body?.string().orEmpty()
            return try {
                JSONArray(body)
            } catch (e: Exception) {
                Log.w(TAG, "Réponse JSON inattendue pour $url : ${e.message}")
                null
            }
        }
    }

    /** Extrait le stream_id final d'une URL Xtream (.../movie/user/pass/12345.mp4 -> 12345). */
    fun extractStreamId(streamUrl: String): Int {
        val lastSegment = streamUrl.substringAfterLast('/')
        val withoutExtension = lastSegment.substringBeforeLast('.')
        return withoutExtension.toIntOrNull() ?: -1
    }

    /**
     * Complète le group-title (catégorie) des chaînes quand le M3U du panel
     * Xtream ne le fournit pas, en s'appuyant sur l'API JSON native du panel
     * (get_vod_categories / get_live_categories). Ne touche jamais un
     * group-title déjà présent - best effort : si l'appel échoue, on garde
     * les chaînes telles quelles (jamais bloquant pour le chargement).
     *
     * Seuls les Films (VOD) et le Live sont couverts pour l'instant : les
     * Séries listent des épisodes individuels dont l'identifiant dans le M3U
     * ne correspond pas au series_id de l'API.
     */
    suspend fun enrichChannelsWithCategories(playlist: SavedPlaylist, channels: List<Channel>): List<Channel> {
        if (playlist.mode != PlaylistMode.XTREAM) return channels

        val needsVod = channels.any { it.contentType() == ContentType.MOVIE && it.groupTitle.isNullOrBlank() }
        val needsLive = channels.any { it.contentType() == ContentType.LIVE && it.groupTitle.isNullOrBlank() }
        if (!needsVod && !needsLive) return channels

        val vodMap = if (needsVod) fetchCategoryMap(playlist, Kind.VOD) else emptyMap()
        val liveMap = if (needsLive) fetchCategoryMap(playlist, Kind.LIVE) else emptyMap()
        if (vodMap.isEmpty() && liveMap.isEmpty()) return channels

        return channels.map { channel ->
            if (!channel.groupTitle.isNullOrBlank()) return@map channel
            val map = when (channel.contentType()) {
                ContentType.MOVIE -> vodMap
                ContentType.LIVE -> liveMap
                else -> return@map channel
            }
            val category = map[extractStreamId(channel.streamUrl)] ?: return@map channel
            channel.copy(groupTitle = category)
        }
    }

    enum class Kind(val categoriesAction: String, val streamsAction: String) {
        VOD("get_vod_categories", "get_vod_streams"),
        LIVE("get_live_categories", "get_live_streams")
    }
}
