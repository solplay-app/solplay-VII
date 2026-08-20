package com.solplay.iptv

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL   = "extra_stream_url"
        const val EXTRA_STREAM_NAME  = "extra_stream_name"
        const val EXTRA_RESUME_POS   = "extra_resume_pos_ms"
        private const val TITLE_DISPLAY_MS = 5000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var sideAdapter: ChannelAdapter
    private var sideChannels: List<Channel> = emptyList()
    private var activePlaylist: SavedPlaylist? = null
    private var programInfoJob: Job? = null
    private var assignmentWatcherJob: Job? = null
    private var hasRetriedAfterRefresh = false

    /**
     * CORRECTIF (bug "lecture s'arrête / Impossible de lire") : la plupart
     * des erreurs ExoPlayer sur IPTV sont de simples micro-coupures réseau
     * (wifi, box du fournisseur, etc.), pas un abonnement expiré ou un lien
     * cassé. Avant de déclencher la lourde procédure (vérifier le compte
     * puis recharger toute la playlist), on retente d'abord 2 fois, en
     * douceur, la lecture du MÊME flux avec un court délai. Ça résout la
     * grande majorité des coupures sans jamais afficher d'erreur à
     * l'utilisateur. On ne passe à la procédure lourde que si ces
     * tentatives rapides échouent aussi.
     */
    private var quickRetryCount = 0
    private val maxQuickRetries = 2

    /**
     * CORRECTIF (bug "la liste des films sort quand j'avance le film") :
     * la flèche droite de la télécommande servait à la fois à faire
     * avancer la lecture ET à ouvrir le panneau de liste, ce qui faisait
     * apparaître la liste au lieu de faire avancer le film/série. Le
     * panneau ne doit s'ouvrir que sur un double-appui rapide du bouton OK
     * (comme sur une télécommande TV/Android box classique) ; la flèche
     * droite/gauche sert désormais uniquement à avancer/reculer la lecture
     * en VOD (film/série), et au zapping direct en live (déjà géré par
     * haut/bas).
     */
    private var lastOkPressTime = 0L
    private val doubleOkThresholdMs = 400L
    private val seekStepMs = 10_000L

    /**
     * CORRECTIF (bug "coupure en pleine lecture, obligé de reprendre la
     * télécommande" + "impossible de relire avant 10 minutes ou plus") :
     * une fois les tentatives rapides ET le rafraîchissement de playlist
     * épuisés (voir handlePlaybackError), l'app affichait un simple Toast
     * puis restait figée sans rien retenter - l'utilisateur devait s'en
     * apercevoir seul et relancer manuellement. Certains fournisseurs IPTV
     * limitent les connexions simultanées par compte et mettent plusieurs
     * minutes à libérer une connexion restée mal fermée (ex: app tuée
     * brutalement) ; ça ne dépend pas de la connexion internet du client.
     * On ne peut pas réduire ce délai côté app, mais on peut éviter d'exiger
     * une action manuelle : nouvelle tentative automatique et silencieuse
     * toutes les 30s, jusqu'à 12 minutes, avec un bouton "Réessayer
     * maintenant" toujours visible pour ne pas attendre si l'utilisateur
     * préfère agir tout de suite.
     */
    private val backgroundRetryHandler = Handler(Looper.getMainLooper())
    private var backgroundRetryRunnable: Runnable? = null
    private var backgroundRetryAttempt = 0
    private val maxBackgroundRetryAttempts = 24 // 24 x 30s ≈ 12 minutes
    private val backgroundRetryIntervalMs = 30_000L

    /**
     * Préchargement des chaînes voisines (Live uniquement, pour un zapping
     * quasi instantané) : 2 instances ExoPlayer légères, préparées en
     * arrière-plan (playWhenReady = false, juste assez de buffer pour
     * démarrer) pour la chaîne juste avant et juste après la chaîne en
     * cours dans [sideChannels]. Quand l'utilisateur zappe vers l'une des
     * deux, on substitue directement ce lecteur déjà prêt au lecteur
     * principal (tryFastSwap) au lieu de repartir de zéro - élimine la
     * poignée de main réseau + le temps de bufferisation initial, qui est
     * l'essentiel du délai perçu au zapping sur IPTV.
     *
     * Compromis assumé : ça consomme un peu plus de bande passante/mémoire
     * en continu (jusqu'à 3 flux ouverts simultanément) - limité
     * volontairement aux seules chaînes Live (VOD/séries n'ont pas besoin
     * d'un zapping instantané, et ce serait un gâchis de bande passante).
     */
    private val preloadPlayers = mutableMapOf<String, ExoPlayer>()

    /** Chaîne actuellement en lecture (pour favoris / catch-up / reprise). */
    private var currentChannel: Channel? = null
    private var currentIsFavorite = false

    /** Position à laquelle reprendre (VOD) dès que le prochain flux sera prêt - consommée une seule fois. */
    private var resumePosForNextReady = 0L

    /** Cycle des ratios d'image : FIT (défaut) → FILL → ZOOM 4:3 → retour FIT */
    private val aspectRatios = listOf("Ajuster", "Remplir", "4:3", "16:9 étiré")
    private var aspectRatioIndex = 0

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        binding.tvChannelTitle.visibility  = View.GONE
        binding.tvProgramInfo.visibility   = View.GONE
        binding.btnChannelList.visibility  = View.GONE
        binding.btnFavorite.visibility     = View.GONE
        binding.btnCatchup.visibility      = View.GONE
        binding.btnAspectRatio.visibility  = View.GONE
    }

    /**
     * CORRECTIF (blocage après une longue période de lecture) : sur IPTV, quand
     * un flux meurt (token/session expiré, serveur qui arrête de servir),
     * ExoPlayer reste bloqué en STATE_BUFFERING SANS jamais déclencher
     * onPlayerError - donc aucune des récupérations existantes ne se lance et
     * l'app semble figée (spinner ou image gelée). Ce watchdog surveille le
     * temps passé en buffering : s'il dépasse [bufferingStallMs], on force une
     * récupération avec une URL FRAÎCHE (re-fetch de la playlist), au lieu de
     * retenter indéfiniment la même URL morte.
     */
    private var bufferingSinceMs = 0L
    private var recoveringFromStall = false
    private val bufferingStallMs = 25_000L
    private val stallCheckHandler = Handler(Looper.getMainLooper())
    private val stallCheckRunnable = object : Runnable {
        override fun run() {
            checkForStalledBuffering()
            stallCheckHandler.postDelayed(this, 5_000L)
        }
    }

    // ──────────────────────────────────────────────────────────
    // onCreate
    // ──────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val startUrl  = intent.getStringExtra(EXTRA_STREAM_URL)  ?: return
        val startName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""
        resumePosForNextReady = intent.getLongExtra(EXTRA_RESUME_POS, 0L)

        initPlayer()

        setupSidePanel()
        playStream(startUrl, startName)

        // Tap écran → contrôles 5s
        binding.playerView.setOnClickListener { showControlsTemporarily() }

        // Bouton liste chaînes
        binding.btnChannelList.setOnClickListener { toggleSidePanel() }

        // ── Ratio d'image ──
        binding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }

        // ── Favori ──
        binding.btnFavorite.setOnClickListener {
            val ch = currentChannel ?: return@setOnClickListener
            currentIsFavorite = FavoritesStore.toggle(this, ch)
            updateFavButton()
            Toast.makeText(
                this,
                if (currentIsFavorite) "⭐ Ajouté aux favoris" else "Retiré des favoris",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ── Catch-up ──
        binding.btnCatchup.setOnClickListener { openCatchup() }
        binding.btnRetryNow.setOnClickListener { retryCurrentChannelNow() }

        setupSideSearch()
        startAssignmentWatcher()
    }

    /**
     * Construit un MediaItem, avec un ciblage explicite du "direct" (live
     * edge) UNIQUEMENT pour les chaînes Live, au lieu du comportement par
     * défaut d'ExoPlayer.
     *
     * CORRECTIF : cette configuration "live" (cible de latence + vitesse de
     * lecture ajustable 0.98x-1.04x) était auparavant appliquée à TOUTES les
     * lectures, y compris les films/séries en VOD - elle est sans effet
     * concret sur un contenu à durée fixe (ExoPlayer l'ignore hors flux
     * réellement signalé "live"), mais reste un non-sens à corriger : pour
     * la VOD, on construit maintenant un MediaItem simple, sans aucun
     * ajustement de vitesse ni ciblage de latence.
     *
     * Sans le ciblage "live" ci-dessous, un flux live IPTV visionné pendant
     * plusieurs heures d'affilée a tendance à dériver de plus en plus loin
     * derrière le vrai direct (le buffer s'accumule progressivement) - un
     * défaut classique des flux live mal configurés, que les vraies apps
     * IPTV corrigent en donnant à ExoPlayer une cible de latence à
     * maintenir : le lecteur ajuste alors très légèrement sa vitesse de
     * lecture (imperceptible à l'oreille/l'œil) pour rattraper ou ralentir
     * automatiquement et rester proche de cette cible, plutôt que de dériver
     * sans contrôle.
     */
    private fun buildMediaItem(url: String, isLive: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (isLive) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(12_000) // ~12s derrière le direct : marge raisonnable pour absorber les micro-coupures sans lag perceptible
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )
        }
        return builder.build()
    }

    /** Raccourci pour les URLs dont on sait déjà (par construction) qu'elles sont Live. */
    private fun buildLiveMediaItem(url: String): MediaItem = buildMediaItem(url, isLive = true)

    /** Construit le MediaItem adapté en se basant sur le type réel de la chaîne. */
    private fun buildMediaItemFor(channel: Channel): MediaItem =
        buildMediaItem(channel.streamUrl, isLive = channel.contentType() == ContentType.LIVE)

    /**
     * Configuration du buffer ExoPlayer adaptée à l'IPTV, au lieu des
     * réglages par défaut (pensés pour du streaming classique type
     * YouTube/Netflix, sur des CDN stables).
     *
     * CORRECTIF (bug "lecture très saccadée, trop de coupures") : les
     * précédents réglages (bufferForPlaybackMs=1.5s, bufferForPlaybackAfter
     * RebufferMs=3s) étaient TROP agressifs pour un flux IPTV, dont le débit
     * réseau est justement irrégulier. Avec une marge aussi faible, le
     * lecteur repart dès qu'il a 1.5-3s de buffer, décroche presque aussitôt
     * à la moindre fluctuation, rebufferise avec à peine plus de marge,
     * redécroche… ce qui crée une VÉRITABLE BOUCLE DE SACCADES au lieu
     * d'absorber les instabilités réseau (l'inverse de l'effet recherché).
     * On remonte donc ces deux seuils à des valeurs qui laissent une vraie
     * marge d'absorption, au prix d'un tout petit délai de démarrage/reprise
     * supplémentaire (à peine perceptible, largement compensé par une
     * lecture stable) :
     * - minBufferMs (20s) / maxBufferMs (40s) : buffer cible confortable
     *   pour amortir les ralentissements typiques d'un flux IPTV, sans
     *   accumuler un retard excessif sur le direct.
     * - bufferForPlaybackMs (3s) : buffer nécessaire pour DÉMARRER la
     *   lecture - proche du défaut ExoPlayer (2.5s), pour un vrai coussin
     *   dès le premier démarrage/changement de chaîne.
     * - bufferForPlaybackAfterRebufferMs (6s) : buffer nécessaire pour
     *   REPRENDRE après une coupure - supérieur au défaut (5s) : c'est le
     *   réglage le plus déterminant contre les boucles de saccades, car
     *   c'est lui qui évite de repartir trop tôt juste pour redécrocher
     *   aussitôt.
     *
     * CORRECTIF (bug "la lecture finit par se bloquer après un moment") :
     * par défaut, ExoPlayer conserve aussi un "back buffer" (les données
     * déjà JOUÉES, gardées en mémoire pour permettre un retour arrière
     * rapide). En VOD ce n'est jamais un problème (durée finie), mais en
     * LIVE - regardé parfois pendant des heures d'affilée - ce back buffer
     * n'est PAS borné par défaut : il grossit indéfiniment tout au long du
     * visionnage, jusqu'à épuiser la mémoire disponible et faire se bloquer
     * ou planter la lecture après un long moment (exactement le symptôme
     * décrit). setBackBuffer borne ce buffer à une fenêtre
     * modeste (30s, juste assez pour absorber d'éventuels petits retours en
     * arrière) : au-delà, les données déjà jouées sont libérées AU FUR ET À
     * MESURE que la lecture avance, au lieu de s'accumuler sans limite.
     */
    private val iptvLoadControl by lazy {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 40_000, 3_000, 6_000)
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * CORRECTIF (bug "trop de coupures" - cause la plus fréquente) : par
     * défaut, quand un simple SEGMENT/chunk réseau échoue à charger (timeout,
     * paquet perdu, micro-décrochage du serveur IPTV...), ExoPlayer ne
     * retente que 1 à 4 fois selon le type de contenu avant de faire
     * remonter l'erreur jusqu'à onPlayerError - qui déclenchait jusqu'ici un
     * VRAI redémarrage visible du flux (écran noir + rebufferisation
     * complète), pour un incident réseau qui se serait souvent résolu tout
     * seul en une fraction de seconde si on avait juste réessayé une fois de
     * plus. On augmente donc le nombre de tentatives internes ET on ajoute
     * un court délai progressif entre elles (backoff), pour que ExoPlayer
     * absorbe lui-même les instabilités réseau typiques de l'IPTV SANS
     * jamais que l'utilisateur ne voie la moindre coupure - avant même que
     * notre récupération applicative (handlePlaybackError) n'ait besoin
     * d'intervenir.
     */
    private class IptvLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // Backoff progressif plafonné : 500ms, 1s, 2s, 4s, puis 4s max.
            val attempt = loadErrorInfo.errorCount
            return minOf(500L shl (attempt - 1).coerceAtLeast(0), 4_000L)
        }
    }

    /** MediaSourceFactory partagée (lecteur principal + préchargements),
     *  configurée avec [IptvLoadErrorHandlingPolicy] ci-dessus. */
    private val iptvMediaSourceFactory by lazy {
        DefaultMediaSourceFactory(this)
            .setLoadErrorHandlingPolicy(IptvLoadErrorHandlingPolicy())
    }


    // ──────────────────────────────────────────────────────────
    // Création / recréation du lecteur
    // ──────────────────────────────────────────────────────────
    private fun initPlayer() {
        if (player != null) return // déjà prêt (ex: tout premier onCreate)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(iptvMediaSourceFactory)
            .setLoadControl(iptvLoadControl)
            .build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            showBuffering(true)
                            // Début du buffer (watchdog anti-blocage) : on note
                            // l'instant, sauf si on y était déjà (buffering continu).
                            if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
                        }
                        Player.STATE_READY     -> {
                            showBuffering(false)
                            bufferingSinceMs = 0L
                            binding.errorOverlay.visibility = View.GONE
                            cancelBackgroundRetry()
                            // La lecture est repartie normalement : on remet à zéro
                            // TOUS les drapeaux de récupération (pas seulement
                            // quickRetryCount). hasRetriedAfterRefresh manquait ici :
                            // sans ça, l'étape "rafraîchir la playlist" (handlePlaybackError,
                            // étape 2) restait sautée pour le reste de la session après
                            // sa première utilisation, même réussie - le filet de
                            // sécurité (réessai en arrière-plan) rattrapait déjà le
                            // coup dans ce cas, mais autant remettre cette étape
                            // intermédiaire disponible aussi, pour une récupération
                            // plus rapide (avant le délai de 30s du réessai en fond).
                            quickRetryCount = 0
                            hasRetriedAfterRefresh = false
                            // Reprise VOD : seek au point mémorisé, consommé une seule fois.
                            val resumePos = resumePosForNextReady
                            if (resumePos > 0 && exo.currentPosition < 1000) {
                                exo.seekTo(resumePos)
                            }
                            resumePosForNextReady = 0L
                        }
                        else -> showBuffering(false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    showBuffering(false)
                    if (tryRecoverBehindLiveWindow(exo, error)) return
                    handlePlaybackError(error)
                }
            })
        }
    }

    /**
     * CORRECTIF (bug "trop de coupures" en direct) : `ERROR_CODE_BEHIND_LIVE_
     * WINDOW` est de très loin l'erreur ExoPlayer la plus fréquente sur un
     * flux IPTV live - elle survient dès que la position de lecture prend
     * trop de retard sur la fenêtre live disponible côté serveur (léger
     * ralentissement réseau, serveur qui purge ses anciens segments...).
     * Avant ce correctif, cette erreur (pourtant totalement bénigne et
     * habituelle en live) déclenchait la MÊME procédure lourde que n'importe
     * quelle autre erreur : tentatives différées, puis rafraîchissement de
     * playlist - donc plusieurs secondes de coupure visible pour un
     * problème qui se résout normalement en un instant.
     * La vraie récupération, standard pour ce type d'erreur précis, est
     * immédiate et ne recharge même pas l'URL : on repositionne juste la
     * lecture sur le direct (`seekToDefaultPosition`) et on relance -
     * aucun écran noir, aucun délai d'attente, aucune reconnexion réseau.
     * Retourne true si l'erreur a été absorbée ainsi (l'appelant ne doit
     * alors PAS déclencher la procédure de récupération lourde).
     */
    private fun tryRecoverBehindLiveWindow(exo: ExoPlayer, error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        if (currentChannel?.contentType() != ContentType.LIVE) return false
        exo.seekToDefaultPosition()
        exo.prepare()
        return true
    }

    /**
     * IMPORTANT (correctif) : le lecteur est entièrement libéré dans onStop()
     * (voir plus bas) pour ne pas garder de ressources vidéo/réseau en
     * arrière-plan. Mais sans ce onStart(), si l'Activity repasse par
     * onStop() PUIS onStart() sans jamais être totalement recréée par
     * Android (cas très courant : mise en veille de l'écran, notification,
     * app qui repasse rapidement au premier plan...), `player` restait
     * définitivement à `null` - toute tentative de lecture après ça ne
     * faisait plus rien (appel silencieux sur un `player?.` nul), ce qui
     * correspond exactement au symptôme "après un moment, impossible de
     * lire quoi que ce soit". On recrée maintenant le lecteur ici et on
     * relance automatiquement la chaîne en cours, à la position mémorisée.
     */
    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
        // Watchdog anti-blocage : démarre dès que l'écran lecteur est visible.
        stallCheckHandler.post(stallCheckRunnable)
        if (player == null) {
            val ch = currentChannel
            initPlayer()
            if (ch != null) {
                val saved = ResumeStore.get(this)
                resumePosForNextReady = if (saved?.streamUrl == ch.streamUrl) saved.positionMs else 0L
                playStreamInternal(ch.streamUrl, ch.name)
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Détection réseau réelle (au lieu d'attendre le prochain cycle
    // de réessai programmé, jusqu'à 30s de délai à l'aveugle)
    // ──────────────────────────────────────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * S'abonne aux changements réels de connectivité : dès que le réseau
     * revient (Wi-Fi/4G qui se reconnecte), on relance IMMÉDIATEMENT la
     * lecture si l'écran d'erreur est affiché - plutôt que d'attendre le
     * prochain cycle programmé du réessai en arrière-plan (jusqu'à 30s
     * d'attente inutile alors que le réseau est déjà revenu).
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!isFinishing && binding.errorOverlay.visibility == View.VISIBLE) {
                        retryCurrentChannelNow()
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            // Certains appareils/ROM restreignent cette API - le réessai
            // programmé en arrière-plan reste le filet de sécurité normal.
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { /* déjà désenregistré */ }
        }
        networkCallback = null
    }

    // ──────────────────────────────────────────────────────────
    // Buffering spinner
    // ──────────────────────────────────────────────────────────
    private fun showBuffering(show: Boolean) {
        binding.progressBuffering.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ──────────────────────────────────────────────────────────
    // Zoom / Ratio d'image
    // ──────────────────────────────────────────────────────────
    private fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % aspectRatios.size
        val label = aspectRatios[aspectRatioIndex]
        binding.btnAspectRatio.text = "⛶ $label"

        when (aspectRatioIndex) {
            0 -> { // FIT — comportement par défaut ExoPlayer
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            1 -> { // FILL — remplir l'écran, peut couper les bords
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            2 -> { // ZOOM — recadrage zoom 4:3
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            3 -> { // FIXED_WIDTH — étire en 16:9 plein écran
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            }
        }
        Toast.makeText(this, "Ratio : $label", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────
    // Favori
    // ──────────────────────────────────────────────────────────
    private fun updateFavButton() {
        binding.btnFavorite.text = if (currentIsFavorite) "★ Favori" else "☆ Favori"
    }

    // ──────────────────────────────────────────────────────────
    // Catch-up
    // ──────────────────────────────────────────────────────────
    private fun openCatchup() {
        val ch = currentChannel ?: return
        val playlist = activePlaylist ?: run {
            Toast.makeText(this, "Catch-up nécessite une playlist Xtream.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ch.contentType() != ContentType.LIVE) {
            Toast.makeText(this, "Le catch-up est disponible uniquement pour les chaînes Live.", Toast.LENGTH_SHORT).show()
            return
        }
        val streamId = XtreamApiClient.extractStreamId(ch.streamUrl)
        if (streamId <= 0) {
            Toast.makeText(this, "Chaîne non compatible avec le catch-up.", Toast.LENGTH_SHORT).show()
            return
        }

        val loading = AlertDialog.Builder(this)
            .setTitle("📼 Catch-up / Replay")
            .setMessage("Chargement des replays disponibles…")
            .setCancelable(true)
            .create()
        loading.show()

        lifecycleScope.launch {
            val entries = CatchupStore.fetchCatchup(playlist, streamId, days = 7)
            if (isFinishing) return@launch
            loading.dismiss()

            if (entries.isEmpty()) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("📼 Catch-up indisponible")
                    .setMessage("Ce panel ne propose pas de replay pour cette chaîne, ou le catch-up n'est pas activé sur votre compte.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            // Grouper par date
            val byDate = entries.groupBy { it.date }
            val dates  = byDate.keys.toList()

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("📼 Replay — ${ch.name}")
                .setItems(dates.toTypedArray()) { _, di ->
                    val programs = byDate[dates[di]] ?: return@setItems
                    val labels = programs.map { "  ${it.start}–${it.end}  ${it.title}" }.toTypedArray()
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle(dates[di])
                        .setItems(labels) { _, pi ->
                            val entry = programs[pi]
                            playStream(entry.streamUrl, "${ch.name} · ${entry.date} ${entry.start}")
                        }
                        .setNegativeButton("Retour", null)
                        .show()
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    /**
     * Avance/recule la lecture d'un film ou d'une série d'un pas fixe.
     * Sans effet en direct (live), où avancer/reculer n'a pas de sens.
     */
    private fun seekRelative(deltaMs: Long) {
        val exo = player ?: return
        if (currentChannel?.contentType() == ContentType.LIVE) return
        val duration = exo.duration
        var target = exo.currentPosition + deltaMs
        if (target < 0L) target = 0L
        if (duration != androidx.media3.common.C.TIME_UNSET && target > duration) target = duration
        exo.seekTo(target)
        showControlsTemporarily()
    }

    // ──────────────────────────────────────────────────────────
    // Navigation télécommande D-pad
    // ──────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // OK / Centre → simple appui : affiche les contrôles.
            // Double appui rapide (< 400ms) : ouvre le panneau de liste
            // (chaînes en live, ou films/séries en VOD).
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    // Le panneau est ouvert : laisser le RecyclerView gérer la sélection
                    super.onKeyDown(keyCode, event)
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastOkPressTime <= doubleOkThresholdMs) {
                        lastOkPressTime = 0L
                        toggleSidePanel()
                    } else {
                        lastOkPressTime = now
                        showControlsTemporarily()
                    }
                    true
                }
            }

            // Flèche haut/bas → changer de chaîne directement (sans ouvrir le panneau)
            KeyEvent.KEYCODE_DPAD_UP   -> { navigateChannel(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navigateChannel(+1); true }

            // Flèche droite → avance la lecture (VOD) ; ne fait plus sortir
            // la liste, qui s'ouvre désormais uniquement via double-OK.
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    super.onKeyDown(keyCode, event)
                } else {
                    seekRelative(seekStepMs)
                    true
                }
            }

            // Flèche gauche → recule la lecture (VOD) si le panneau est
            // fermé ; ferme le panneau s'il est ouvert.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    binding.channelListPanel.visibility = View.GONE
                    binding.etSideSearch.text?.clear()
                    showControlsTemporarily()
                    true
                } else {
                    seekRelative(-seekStepMs)
                    true
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    binding.channelListPanel.visibility = View.GONE
                    binding.etSideSearch.text?.clear()
                    showControlsTemporarily()
                    true
                } else {
                    saveResumePosition()
                    super.onKeyDown(keyCode, event)
                }
            }

            // Bouton Pause/Play télécommande
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Préchargement des chaînes voisines (zapping instantané)
    // ──────────────────────────────────────────────────────────

    /** URL de la chaîne juste après [currentChannel] dans la liste, uniquement si Live. */
    private fun neighborLiveUrls(): List<String> {
        val url = currentChannel?.streamUrl ?: return emptyList()
        val idx = sideChannels.indexOfFirst { it.streamUrl == url }
        if (idx < 0) return emptyList()
        // IMPORTANT (correctif) : un seul voisin préchargé (le suivant),
        // pas deux (précédent + suivant) comme avant. De nombreux
        // boîtiers/TV bas de gamme n'ont qu'UN SEUL décodeur vidéo matériel
        // disponible à la fois - garder le lecteur principal + 2 lecteurs
        // préchargés ouverts simultanément pouvait épuiser cette ressource
        // et faire échouer la lecture juste après un changement de chaîne
        // ("je change de chaîne, la suivante ne se lit plus"). Un seul
        // voisin préchargé réduit ce risque tout en gardant l'essentiel du
        // bénéfice (zapping suivant quasi instantané, le sens le plus
        // utilisé en pratique).
        val next = sideChannels.getOrNull(idx + 1) ?: return emptyList()
        return if (next.contentType() == ContentType.LIVE) listOf(next.streamUrl) else emptyList()
    }

    /** Prépare (sans jouer) le lecteur de la chaîne voisine, et libère ceux qui ne sont plus utiles. */
    private fun schedulePreloadNeighbors() {
        val wanted = neighborLiveUrls().toSet()

        // Libère les préchargements devenus inutiles (l'utilisateur a zappé ailleurs).
        val stale = preloadPlayers.keys - wanted
        stale.forEach { url -> preloadPlayers.remove(url)?.release() }

        // Prépare le nouveau voisin, pas encore en cache.
        for (url in wanted) {
            if (preloadPlayers.containsKey(url)) continue
            val exo = ExoPlayer.Builder(this)
                .setMediaSourceFactory(iptvMediaSourceFactory)
                .setLoadControl(iptvLoadControl)
                .build()
            exo.setMediaItem(buildLiveMediaItem(url))
            // IMPORTANT (correctif) : sans ce listener, un préchargement qui
            // échoue (réseau, décodeur indisponible...) restait bloqué en
            // mémoire indéfiniment - potentiellement en train d'occuper une
            // ressource vidéo dont la VRAIE lecture suivante avait besoin -
            // jusqu'à ce qu'un autre changement de chaîne le détecte comme
            // "plus voisin" et le libère enfin. On le libère maintenant
            // IMMÉDIATEMENT en cas d'échec.
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    preloadPlayers.remove(url)?.release()
                }
            })
            exo.playWhenReady = false
            exo.prepare()
            preloadPlayers[url] = exo
        }
    }

    /**
     * Si un lecteur préchargé existe pour [url] et est déjà prêt (assez
     * bufferisé pour démarrer sans à-coup), le substitue directement en
     * lecteur principal - retourne true. Sinon retourne false, et l'appelant
     * doit repartir sur un chargement classique (playStream).
     */
    private fun tryFastSwap(url: String, name: String): Boolean {
        val ready = preloadPlayers[url]?.takeIf { it.playbackState == Player.STATE_READY } ?: return false
        preloadPlayers.remove(url)

        // L'ancien lecteur principal devient inutile (sa chaîne n'est plus
        // forcément un voisin de la nouvelle position) - on le libère
        // simplement, plus simple/sûr que de tenter de le recycler.
        player?.release()

        hasRetriedAfterRefresh = false
        quickRetryCount = 0
        player = ready
        binding.playerView.player = ready
        ready.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                showBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    binding.errorOverlay.visibility = View.GONE
                    cancelBackgroundRetry()
                    quickRetryCount = 0
                    hasRetriedAfterRefresh = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                showBuffering(false)
                if (tryRecoverBehindLiveWindow(ready, error)) return
                handlePlaybackError(error)
            }
        })
        ready.playWhenReady = true

        currentChannel = Channel(name = name, logoUrl = null, groupTitle = null, streamUrl = url)
        currentIsFavorite = FavoritesStore.isFavorite(this, url)
        updateFavButton()
        binding.btnCatchup.visibility =
            if (currentChannel?.contentType() == ContentType.LIVE && activePlaylist?.extractXtreamCredentials() != null)
                View.VISIBLE else View.GONE
        binding.tvChannelTitle.text = name
        showBuffering(false)
        loadProgramInfo(url)
        showControlsTemporarily()

        schedulePreloadNeighbors()
        return true
    }

    private fun releaseAllPreloads() {
        preloadPlayers.values.forEach { it.release() }
        preloadPlayers.clear()
    }

    private fun navigateChannel(delta: Int) {
        val url = currentChannel?.streamUrl ?: return
        val idx = sideChannels.indexOfFirst { it.streamUrl == url }
        if (idx < 0) return
        val next = (idx + delta).coerceIn(0, sideChannels.lastIndex)
        if (next == idx) return
        val ch = sideChannels[next]
        if (!tryFastSwap(ch.streamUrl, ch.name)) {
            playStream(ch.streamUrl, ch.name)
        }
        showControlsTemporarily()
    }

    // ──────────────────────────────────────────────────────────
    // Assignment watcher (admin)
    // ──────────────────────────────────────────────────────────
    private fun startAssignmentWatcher() {
        val tag = activePlaylist?.fromCode ?: return
        if (!tag.startsWith("device:")) return
        assignmentWatcherJob = lifecycleScope.launch {
            while (true) {
                val ok = DevicePlaylistSync.checkStillAssigned(this@PlayerActivity, tag)
                if (!ok) {
                    Toast.makeText(this@PlayerActivity,
                        "L'accès à cette playlist a été retiré par l'administrateur.",
                        Toast.LENGTH_LONG).show()
                    player?.stop(); finish(); break
                }
                kotlinx.coroutines.delay(20_000L)
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Fullscreen
    // ──────────────────────────────────────────────────────────
    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    // ──────────────────────────────────────────────────────────
    // Side panel
    // ──────────────────────────────────────────────────────────
    private fun setupSidePanel() {
        sideChannels   = ChannelRepository.playingList
        activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }

        sideAdapter = ChannelAdapter(
            sideChannels,
            itemLayoutRes = R.layout.item_channel_dark,
            epgPlaylist   = activePlaylist,
            onLongClick   = { channel ->
                // Long-press dans le lecteur → page détail Film/Série
                if (channel.contentType() != ContentType.LIVE) {
                    val intent = android.content.Intent(this, DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_CHANNEL_URL,   channel.streamUrl)
                        putExtra(DetailActivity.EXTRA_CHANNEL_NAME,  channel.name)
                        putExtra(DetailActivity.EXTRA_CHANNEL_LOGO,  channel.logoUrl)
                        putExtra(DetailActivity.EXTRA_CHANNEL_GROUP, channel.groupTitle)
                    }
                    startActivity(intent)
                }
            }
        ) { channel ->
            if (!tryFastSwap(channel.streamUrl, channel.name)) {
                playStream(channel.streamUrl, channel.name)
            }
            binding.channelListPanel.visibility = View.GONE
            binding.etSideSearch.text?.clear()
        }
        binding.recyclerSideChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerSideChannels.adapter = sideAdapter

        binding.btnChannelList.visibility =
            if (sideChannels.size > 1) View.VISIBLE else View.GONE
    }

    private fun setupSideSearch() {
        binding.etSideSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                sideAdapter.updateData(
                    if (q.isEmpty()) sideChannels
                    else sideChannels.filter { it.name.contains(q, ignoreCase = true) }
                )
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun toggleSidePanel() {
        val opening = binding.channelListPanel.visibility != View.VISIBLE
        binding.channelListPanel.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) {
            showControlsTemporarily(keepVisible = true)
            // Focus sur la RecyclerView pour navigation D-pad
            binding.recyclerSideChannels.requestFocus()
        } else {
            binding.etSideSearch.text?.clear()
            showControlsTemporarily()
        }
    }

    /**
     * Vérifie toutes les 5s si ExoPlayer est bloqué en buffering depuis trop
     * longtemps (flux mort sans onPlayerError) et, si oui, déclenche la
     * récupération avec une URL fraîche.
     */
    private fun checkForStalledBuffering() {
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_BUFFERING) {
            bufferingSinceMs = 0L
            return
        }
        if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
        if (System.currentTimeMillis() - bufferingSinceMs < bufferingStallMs) return
        recoverFromStall()
    }

    /**
     * Récupération d'un flux mort : recharge la playlist pour obtenir une URL
     * fraîche de la même chaîne et relance la lecture. Si le rechargement
     * échoue, on retente la même URL une dernière fois puis on affiche
     * l'overlay d'erreur avec les réessais automatiques en arrière-plan.
     */
    private fun recoverFromStall() {
        if (isFinishing || recoveringFromStall) return
        val ch = currentChannel ?: return
        recoveringFromStall = true
        // On affiche maintenant un statut clair PENDANT la récupération (au
        // lieu de laisser le spinner tourner seul 25s sans explication) :
        // plus rassurant/professionnel, et cohérent avec le comportement de
        // handlePlaybackError ci-dessous.
        showBuffering(false)
        showErrorOverlay(
            message = "Signal interrompu.",
            subMessage = "Récupération d'une nouvelle adresse…",
            scheduleBackgroundRetry = false
        )
        Log.w("SOLPLAY", "Stall détecté après ${bufferingStallMs / 1000}s de buffer sur '${ch.name}'")
        lifecycleScope.launch {
            val playlist = activePlaylist
            val fresh = if (playlist != null) {
                val refreshed = ChannelRefresher.refresh(this@PlayerActivity, playlist)
                refreshed?.firstOrNull { it.name == ch.name }
            } else null
            recoveringFromStall = false
            if (isFinishing) return@launch
            if (fresh != null) {
                Log.i("SOLPLAY", "URL fraîche obtenue pour '${ch.name}'")
                quickRetryCount = 0
                hasRetriedAfterRefresh = false
                playStreamInternal(fresh.streamUrl, fresh.name)
            } else {
                // Re-fetch impossible : on retente la même URL une dernière fois,
                // puis on passe sur l'overlay d'erreur + réessais en arrière-plan.
                player?.apply {
                    setMediaItem(buildMediaItemFor(ch))
                    prepare()
                    playWhenReady = true
                }
                showErrorOverlay(
                    message = "Cette chaîne n'est pas disponible pour le moment.",
                    subMessage = "Nouvelle tentative automatique en cours…",
                    scheduleBackgroundRetry = true
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Erreur de lecture
    // ──────────────────────────────────────────────────────────

    /**
     * CORRECTIF (bug "tourne en rond au lieu d'afficher une erreur claire") :
     * certaines erreurs ExoPlayer signifient que le flux est DÉFINITIVEMENT
     * mort (lien expiré/retiré côté panel, chaîne inexistante, format
     * illisible...) - retenter rapidement la MÊME URL plusieurs fois
     * (l'étape 1 "tentatives rapides") ne sert alors à rien et ne fait que
     * retarder inutilement l'affichage d'un message clair à l'utilisateur.
     * On distingue donc :
     *  - les erreurs RÉSEAU/transitoires (timeout, connexion perdue...) →
     *    on tente d'abord une récupération rapide et silencieuse (l'écran
     *    ne bouge pas), car elles se résolvent souvent seules en 1-2s.
     *  - les erreurs DÉFINITIVES (HTTP 403/404, permission refusée, flux
     *    illisible/mal formé) → on saute directement à la vérification
     *    côté serveur (compte / URL fraîche), qui est le seul recours utile,
     *    pour arriver plus vite à un message d'erreur explicite si rien n'y
     *    fait - au lieu de faire tourner un spinner sans explication pendant
     *    plusieurs secondes pour rien.
     */
    private fun isDefinitelyDeadStream(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> true
        else -> false
    }

    private fun handlePlaybackError(error: PlaybackException? = null) {
        val ch = currentChannel
        val definitelyDead = error != null && isDefinitelyDeadStream(error)

        // Étape 1 : tentatives rapides et silencieuses sur le MÊME flux -
        // UNIQUEMENT pour les erreurs transitoires (réseau). Pour un flux
        // qu'on sait déjà définitivement mort, ça n'aurait aucune chance de
        // marcher : on passe directement à l'étape 2.
        if (!definitelyDead && quickRetryCount < maxQuickRetries && ch != null) {
            quickRetryCount++
            val delayMs = 1200L * quickRetryCount // 1.2s puis 2.4s
            hideHandler.postDelayed({
                if (!isFinishing) {
                    player?.apply {
                        setMediaItem(buildMediaItemFor(ch))
                        prepare()
                        playWhenReady = true
                    }
                }
            }, delayMs)
            return
        }

        // Étape 2 : les tentatives rapides ont échoué (ou étaient inutiles)
        // -> il peut vraiment y avoir un problème de compte / de lien cassé,
        // on vérifie côté serveur. Cette étape peut prendre quelques
        // secondes (appels réseau) : on affiche désormais un statut clair
        // PENDANT la vérification au lieu de laisser un simple spinner sans
        // explication ("tourne en rond") - plus professionnel, comme les
        // lecteurs IPTV du marché.
        val playlist = activePlaylist ?: run {
            showErrorOverlay(
                message = "Impossible de lire cette chaîne.",
                subMessage = "Vérifiez votre connexion internet.",
                scheduleBackgroundRetry = true
            )
            return
        }
        showBuffering(false)
        showErrorOverlay(
            message = if (definitelyDead) "Cette chaîne semble indisponible." else "Problème de lecture détecté.",
            subMessage = "Vérification en cours…",
            scheduleBackgroundRetry = false
        )
        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist)
            if (isFinishing) return@launch
            if (status?.expired == true) {
                val expiryText = status.expiresAtMillis?.let { TrialManager.formatDate(it) }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("⚠️ Abonnement expiré")
                    .setMessage(buildString {
                        append("Votre abonnement IPTV est arrivé à expiration")
                        if (expiryText != null) append(" le $expiryText")
                        append(".\n\nContactez votre fournisseur pour renouveler votre code.")
                    })
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else if (!hasRetriedAfterRefresh) {
                hasRetriedAfterRefresh = true
                val prevName = binding.tvChannelTitle.text.toString()
                val refreshed = ChannelRefresher.refresh(this@PlayerActivity, playlist)
                val updated = refreshed?.firstOrNull { it.name == prevName }
                if (updated != null && !isFinishing) {
                    binding.tvErrorSubMessage.text = "Adresse actualisée, nouvelle tentative…"
                    playStreamInternal(updated.streamUrl, updated.name)
                } else {
                    // Aucune adresse fraîche disponible : c'est bien un vrai
                    // problème (chaîne retirée du panel, etc.) - message final
                    // clair, pas un simple spinner qui tourne dans le vide.
                    showErrorOverlay(
                        message = "Cette chaîne n'est pas disponible pour le moment.",
                        subMessage = "Nouvelle tentative automatique en cours…",
                        scheduleBackgroundRetry = true
                    )
                }
            } else {
                showErrorOverlay(
                    message = "Cette chaîne n'est pas disponible pour le moment.",
                    subMessage = "Nouvelle tentative automatique en cours…",
                    scheduleBackgroundRetry = true
                )
            }
        }
    }

    /**
     * Affiche l'overlay d'erreur avec un message EXPLICITE (au lieu du texte
     * fixe générique d'avant) et, si demandé, programme les tentatives
     * automatiques en arrière-plan - voir [backgroundRetryHandler].
     * scheduleBackgroundRetry=false pendant une simple vérification en cours
     * (on ne veut pas encore programmer de réessai, l'appelant s'en charge
     * lui-même une fois le résultat connu).
     */
    private fun showErrorOverlay(message: String, subMessage: String, scheduleBackgroundRetry: Boolean) {
        if (isFinishing) return
        val alreadyShowing = binding.errorOverlay.visibility == View.VISIBLE
        binding.tvErrorMessage.text = message
        binding.tvErrorSubMessage.text = subMessage
        binding.errorOverlay.visibility = View.VISIBLE
        if (scheduleBackgroundRetry) {
            if (!alreadyShowing) backgroundRetryAttempt = 0
            scheduleNextBackgroundRetry()
        }
    }

    private fun scheduleNextBackgroundRetry() {
        cancelBackgroundRetry()
        if (backgroundRetryAttempt >= maxBackgroundRetryAttempts) {
            binding.tvErrorSubMessage.text = "Réessaie plus tard, ou contacte ton fournisseur si ça persiste."
            return
        }
        backgroundRetryRunnable = Runnable {
            backgroundRetryAttempt++
            retryCurrentChannelSilently()
        }
        backgroundRetryHandler.postDelayed(backgroundRetryRunnable!!, backgroundRetryIntervalMs)
    }

    private fun cancelBackgroundRetry() {
        backgroundRetryRunnable?.let { backgroundRetryHandler.removeCallbacks(it) }
        backgroundRetryRunnable = null
    }

    /** Tentative automatique, silencieuse (pas de Toast) - se reprogramme elle-même en cas de nouvel échec. */
    private fun retryCurrentChannelSilently() {
        val ch = currentChannel ?: return
        if (isFinishing) return
        quickRetryCount = 0
        hasRetriedAfterRefresh = false
        playStreamInternal(ch.streamUrl, ch.name)
        // Si ça échoue à nouveau, onPlayerError -> handlePlaybackError sera
        // rappelé normalement et reprogrammera la suite via scheduleNextBackgroundRetry
        // (appelé depuis showErrorOverlayAndScheduleBackgroundRetry ci-dessus).
        // On avance juste le sous-texte pour que ce ne soit pas silencieux visuellement.
        binding.tvErrorSubMessage.text = "Nouvelle tentative automatique en cours…"
    }

    /** Appui manuel sur "Réessayer maintenant" : identique, mais immédiat et sans attendre le prochain cycle. */
    private fun retryCurrentChannelNow() {
        cancelBackgroundRetry()
        backgroundRetryAttempt = 0
        val ch = currentChannel ?: return
        quickRetryCount = 0
        hasRetriedAfterRefresh = false
        binding.tvErrorSubMessage.text = "Nouvelle tentative en cours…"
        playStreamInternal(ch.streamUrl, ch.name)
    }

    // ──────────────────────────────────────────────────────────
    // Lecture
    // ──────────────────────────────────────────────────────────
    private fun playStream(url: String, name: String) {
        hasRetriedAfterRefresh = false
        quickRetryCount = 0
        cancelBackgroundRetry()
        backgroundRetryAttempt = 0
        binding.errorOverlay.visibility = View.GONE
        playStreamInternal(url, name)
    }

    private fun playStreamInternal(url: String, name: String) {
        // Mémoriser la chaîne courante
        currentChannel = Channel(
            name       = name,
            logoUrl    = null,
            groupTitle = null,
            streamUrl  = url
        )
        currentIsFavorite = FavoritesStore.isFavorite(this, url)
        updateFavButton()

        // Catch-up uniquement pour chaînes Live
        val isLive = currentChannel?.contentType() == ContentType.LIVE
        binding.btnCatchup.visibility = if (isLive && activePlaylist?.extractXtreamCredentials() != null)
            View.VISIBLE else View.GONE

        binding.tvChannelTitle.text = name
        showBuffering(true)
        // Si un préchargement existait déjà pour cette URL (ex: l'utilisateur
        // a zappé plus vite que le fast-swap, ou un cas où tryFastSwap n'a
        // pas été utilisé), il devient redondant avec le lecteur principal
        // qu'on s'apprête à préparer ci-dessous - on le libère pour éviter
        // deux lecteurs ouverts sur le même flux.
        preloadPlayers.remove(url)?.release()
        player?.apply {
            setMediaItem(buildMediaItem(url, isLive))
            prepare()
            playWhenReady = true
        }
        loadProgramInfo(url)
        showControlsTemporarily()
        schedulePreloadNeighbors()
    }

    // ──────────────────────────────────────────────────────────
    // EPG
    // ──────────────────────────────────────────────────────────
    private fun loadProgramInfo(streamUrl: String) {
        programInfoJob?.cancel()
        binding.tvProgramInfo.visibility = View.GONE
        val playlist = activePlaylist ?: return
        val isLive = Channel(name = "", logoUrl = null, groupTitle = null, streamUrl = streamUrl)
            .contentType() == ContentType.LIVE
        if (!isLive) return
        val streamId = XtreamApiClient.extractStreamId(streamUrl)
        if (streamId <= 0) return
        programInfoJob = lifecycleScope.launch {
            val program = XtreamApiClient.fetchNowPlaying(playlist, streamId) ?: return@launch
            binding.tvProgramInfo.text = "${program.startTime}–${program.endTime} · ${program.title}"
            if (binding.tvChannelTitle.visibility == View.VISIBLE)
                binding.tvProgramInfo.visibility = View.VISIBLE
        }
    }

    // ──────────────────────────────────────────────────────────
    // Contrôles (affichage temporaire)
    // ──────────────────────────────────────────────────────────
    private fun showControlsTemporarily(keepVisible: Boolean = false) {
        binding.tvChannelTitle.visibility = View.VISIBLE
        if (binding.tvProgramInfo.text.isNotEmpty())
            binding.tvProgramInfo.visibility = View.VISIBLE
        if (sideChannels.size > 1) binding.btnChannelList.visibility = View.VISIBLE
        binding.btnFavorite.visibility = View.VISIBLE
        binding.btnAspectRatio.visibility = View.VISIBLE
        val isLive = currentChannel?.contentType() == ContentType.LIVE
        if (isLive && activePlaylist?.extractXtreamCredentials() != null)
            binding.btnCatchup.visibility = View.VISIBLE

        hideHandler.removeCallbacks(hideControlsRunnable)
        if (!keepVisible) hideHandler.postDelayed(hideControlsRunnable, TITLE_DISPLAY_MS)
    }

    // ──────────────────────────────────────────────────────────
    // Reprise (sauvegarde position à la fermeture)
    // ──────────────────────────────────────────────────────────
    private fun saveResumePosition() {
        val ch  = currentChannel ?: return
        val pos = player?.currentPosition ?: 0L
        ResumeStore.save(this, ch, pos)
        // Historique de visionnage : on n'enregistre que si l'utilisateur
        // a regardé au moins 5 secondes (évite les zappings instantanés).
        if (pos > 5_000L || ch.contentType() == ContentType.LIVE) {
            WatchHistoryStore.record(this, ch, pos)
        }
    }

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        hideHandler.removeCallbacks(hideControlsRunnable)
        stallCheckHandler.removeCallbacks(stallCheckRunnable)
        cancelBackgroundRetry()
        unregisterNetworkCallback()
        player?.release()
        player = null
        releaseAllPreloads()
    }
}
