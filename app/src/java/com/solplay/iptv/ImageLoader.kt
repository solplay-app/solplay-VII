package com.solplay.iptv

import android.content.Context
import android.util.Log
import coil.ImageLoader as CoilImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Instance Coil partagée, configurée pour envoyer un en-tête User-Agent
 * "navigateur" sur chaque requête d'image (logos M3U et affiches TMDB).
 *
 * Pourquoi : beaucoup de fournisseurs IPTV protègent leurs logos de chaîne
 * contre le hotlinking et rejettent silencieusement (ou renvoient une image
 * vide/erreur) toute requête sans User-Agent "légitime".
 *
 * Historique : la version précédente utilisait Picasso +
 * picasso2-okhttp3-downloader. Ce dernier n'est plus maintenu depuis 2016 et
 * s'est avéré être la cause des logos de chaîne ET des affiches TMDB qui ne
 * s'affichaient jamais (repli silencieux sur le placeholder), y compris
 * quand la recherche TMDB elle-même réussissait ("TMDB: OK"). Coil embarque
 * nativement OkHttp (pas de couche d'adaptation tierce non maintenue) et
 * gère correctement les redirections, le cache disque et les échecs
 * réseau : bien plus fiable pour ce cas d'usage.
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

    @Volatile
    private var instance: CoilImageLoader? = null

    fun get(context: Context): CoilImageLoader {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): CoilImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("User-Agent", USER_AGENT)
                // Certains hébergeurs de logos de chaînes (picons) refusent les
                // requêtes sans en-tête Referer ("hotlink protection"). On envoie
                // l'origine de l'image elle-même : accepté par la quasi-totalité
                // de ces protections, qui ne vérifient qu'une présence/cohérence
                // basique plutôt qu'un domaine précis.
                if (original.header("Referer") == null) {
                    val url = original.url
                    builder.header("Referer", "${url.scheme}://${url.host}/")
                }
                chain.proceed(builder.build())
            }
            .build()

        return CoilImageLoader.Builder(context)
            .okHttpClient(client)
            .components {
                // Beaucoup de packs de "picons" (logos de chaînes) IPTV sont
                // fournis en SVG plutôt qu'en PNG/JPEG ; sans ce décodeur, Coil
                // échoue silencieusement à afficher ces logos et retombe sur le
                // placeholder, même quand le fichier a bien été téléchargé.
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            // Utile pour diagnostiquer un échec réel de chargement (URL
            // cassée, hôte injoignable...) directement dans Logcat au lieu
            // d'un simple retour silencieux au placeholder.
            .logger(object : coil.util.Logger {
                override var level: Int = Log.WARN
                override fun log(tag: String, priority: Int, message: String?, throwable: Throwable?) {
                    if (priority >= Log.WARN) {
                        Log.println(priority, TAG, message ?: throwable?.message.orEmpty())
                    }
                }
            })
            .respectCacheHeaders(false)
            .build()
    }
}
