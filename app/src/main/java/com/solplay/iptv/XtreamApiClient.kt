package com.solplay.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    /** Programme en cours (ou à venir) pour une chaîne Live. */
    data class EpgProgram(val title: String, val startTime: String, val endTime: String)

    private val epgCache = java.util.Collections.synchronizedMap(mutableMapOf<String, EpgProgram?>())

    /**
     * Récupère le programme en cours pour une chaîne Live via l'API EPG native
     * du panel Xtream (`get_short_epg`). Résultat mis en cache mémoire (clé
     * serveur+stream_id) le temps de la session, pour éviter de re-solliciter
     * le serveur à chaque scroll/re-bind de la RecyclerView.
     *
     * Best effort : renvoie null si le panel ne fournit pas d'EPG pour cette
     * chaîne (fréquent - beaucoup de chaînes n'ont simplement pas de données
     * EPG côté fournisseur), en cas d'erreur réseau, ou hors mode Xtream.
     */
    suspend fun fetchNowPlaying(playlist: SavedPlaylist, streamId: Int): EpgProgram? {
        if (playlist.mode != PlaylistMode.XTREAM || streamId <= 0) return null
        val base = playlist.xtreamServer.trim().trimEnd('/')
        val user = playlist.xtreamUsername.trim()
        val pass = playlist.xtreamPassword.trim()
        if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) return null

        val cacheKey = "$base|$user|$streamId"
        if (epgCache.containsKey(cacheKey)) return epgCache[cacheKey]

        return withContext(Dispatchers.IO) {
            val result = try {
                val url = "$base/player_api.php?username=$user&password=$pass&action=get_short_epg&stream_id=$streamId&limit=1"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val listings = JSONObject(body).optJSONArray("epg_listings") ?: return@use null
                    if (listings.length() == 0) return@use null
                    val first = listings.getJSONObject(0)
                    val title = decodeEpgText(first.optString("title"))
                    if (title.isBlank()) return@use null
                    EpgProgram(
                        title = title,
                        startTime = formatEpgTime(first.optString("start")),
                        endTime = formatEpgTime(first.optString("stop", first.optString("end")))
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Échec EPG stream_id=$streamId : ${e.message}")
                null
            }
            epgCache[cacheKey] = result
            result
        }
    }

    /** Le panel Xtream encode généralement titre/description EPG en Base64. */
    private fun decodeEpgText(value: String): String {
        if (value.isBlank()) return ""
        return try {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            value.trim()
        }
    }

    /** "2026-07-15 20:00:00" -> "20:00". */
    private fun formatEpgTime(raw: String): String {
        val timePart = raw.substringAfter(' ', missingDelimiterValue = raw)
        return timePart.take(5).ifEmpty { raw }
    }

    /** Résultat de la vérification du statut d'un abonnement Xtream. */
    data class AccountStatus(
        val expired: Boolean,
        /** Statut brut renvoyé par le panel ("Active", "Expired", "Banned", "Disabled"...). */
        val statusLabel: String?,
        /** Date d'expiration en millisecondes (epoch), null si non fournie/illimitée. */
        val expiresAtMillis: Long?
    )

    /**
     * Interroge l'API native du panel Xtream (`player_api.php`, sans paramètre
     * `action`) pour connaître le statut réel de l'abonnement : actif, expiré,
     * banni, désactivé... et sa date d'expiration (`exp_date`, en secondes epoch).
     *
     * Fonctionne aussi bien pour une playlist enregistrée en mode Xtream que
     * pour un simple lien M3U qui se trouve être un lien Xtream déguisé
     * (voir SavedPlaylist.extractXtreamCredentials) - c'est justement ce qui
     * permet de détecter l'expiration d'un "code M3U" alors qu'aucune API
     * classique ne l'annoncerait autrement (le fichier M3U continue souvent
     * d'être servi tel quel même après expiration, seuls les flux eux-mêmes
     * cessent de fonctionner).
     *
     * Best effort : renvoie null si les identifiants n'ont pas pu être extraits,
     * en cas d'erreur réseau, ou si le panel ne renvoie pas les champs attendus -
     * dans ce cas on ne peut simplement pas se prononcer sur l'expiration, mais
     * on n'affiche jamais un message d'expiration à tort.
     */
    suspend fun checkAccountStatus(playlist: SavedPlaylist): AccountStatus? {
        val (server, user, pass) = playlist.extractXtreamCredentials() ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val url = "$server/player_api.php?username=$user&password=$pass"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val userInfo = JSONObject(body).optJSONObject("user_info") ?: return@use null

                    val statusLabel = userInfo.optString("status").takeIf { it.isNotBlank() }
                    val expDateRaw = userInfo.optString("exp_date").takeIf { it.isNotBlank() && it != "null" }
                    val expiresAtMillis = expDateRaw?.toLongOrNull()?.times(1000L)

                    val now = System.currentTimeMillis()
                    val expiredByDate = expiresAtMillis != null && expiresAtMillis < now
                    // Certains panels renvoient un status explicite ("Expired", "Banned",
                    // "Disabled") indépendamment de exp_date : on considère expiré/bloqué
                    // dès que ce n'est pas "Active", en plus de la date elle-même.
                    val expiredByStatus = statusLabel != null && !statusLabel.equals("Active", ignoreCase = true)

                    AccountStatus(
                        expired = expiredByDate || expiredByStatus,
                        statusLabel = statusLabel,
                        expiresAtMillis = expiresAtMillis
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Échec vérification statut abonnement : ${e.message}")
                null
            }
        }
    }
}
