package com.solplay.iptv

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import androidx.media3.exoplayer.ExoPlayer
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

    // ──────────────────────────────────────────────────────────
    // Création / recréation du lecteur
    // ──────────────────────────────────────────────────────────
    private fun initPlayer() {
        if (player != null) return // déjà prêt (ex: tout premier onCreate)
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> showBuffering(true)
                        Player.STATE_READY     -> {
                            showBuffering(false)
                            binding.errorOverlay.visibility = View.GONE
                            cancelBackgroundRetry()
                            // La lecture est repartie normalement : on remet le compteur
                            // de tentatives rapides à zéro pour la prochaine coupure.
                            quickRetryCount = 0
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
                    handlePlaybackError()
                }
            })
        }
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

    // ──────────────────────────────────────────────────────────
    // Navigation télécommande D-pad
    // ──────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // OK / Centre → affiche les contrôles (même comportement que tap écran)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    // Le panneau est ouvert : laisser le RecyclerView gérer la sélection
                    super.onKeyDown(keyCode, event)
                } else {
                    showControlsTemporarily()
                    true
                }
            }

            // Flèche haut/bas → changer de chaîne directement (sans ouvrir le panneau)
            KeyEvent.KEYCODE_DPAD_UP   -> { navigateChannel(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navigateChannel(+1); true }

            // Flèche droite → ouvre le panneau chaînes
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.channelListPanel.visibility != View.VISIBLE) {
                    toggleSidePanel()
                    true
                } else super.onKeyDown(keyCode, event)
            }

            // Flèche gauche / Retour → ferme le panneau si ouvert, sinon quitte
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (binding.channelListPanel.visibility == View.VISIBLE) {
                    binding.channelListPanel.visibility = View.GONE
                    binding.etSideSearch.text?.clear()
                    showControlsTemporarily()
                    true
                } else super.onKeyDown(keyCode, event)
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

    /** URLs des chaînes juste avant/après [currentChannel] dans la liste, uniquement si Live. */
    private fun neighborLiveUrls(): List<String> {
        val url = currentChannel?.streamUrl ?: return emptyList()
        val idx = sideChannels.indexOfFirst { it.streamUrl == url }
        if (idx < 0) return emptyList()
        val neighbors = listOfNotNull(sideChannels.getOrNull(idx - 1), sideChannels.getOrNull(idx + 1))
        return neighbors.filter { it.contentType() == ContentType.LIVE }.map { it.streamUrl }
    }

    /** Prépare (sans jouer) les lecteurs des chaînes voisines, et libère ceux qui ne sont plus utiles. */
    private fun schedulePreloadNeighbors() {
        val wanted = neighborLiveUrls().toSet()

        // Libère les préchargements devenus inutiles (l'utilisateur a zappé ailleurs).
        val stale = preloadPlayers.keys - wanted
        stale.forEach { url -> preloadPlayers.remove(url)?.release() }

        // Prépare les nouveaux voisins pas encore en cache.
        for (url in wanted) {
            if (preloadPlayers.containsKey(url)) continue
            val exo = ExoPlayer.Builder(this).build()
            exo.setMediaItem(MediaItem.fromUri(url))
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
        player = ready
        binding.playerView.player = ready
        ready.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                showBuffering(state == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(error: PlaybackException) {
                showBuffering(false)
                handlePlaybackError()
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

    // ──────────────────────────────────────────────────────────
    // Erreur de lecture
    // ──────────────────────────────────────────────────────────
    private fun handlePlaybackError() {
        // Étape 1 : tentatives rapides et silencieuses sur le MÊME flux.
        // Couvre le cas très fréquent d'une micro-coupure réseau qui se
        // résout d'elle-même en 1-2 secondes - l'utilisateur ne voit rien.
        val ch = currentChannel
        if (quickRetryCount < maxQuickRetries && ch != null) {
            quickRetryCount++
            val delayMs = 1200L * quickRetryCount // 1.2s puis 2.4s
            hideHandler.postDelayed({
                if (!isFinishing) {
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(ch.streamUrl))
                        prepare()
                        playWhenReady = true
                    }
                }
            }, delayMs)
            return
        }

        // Étape 2 : les tentatives rapides ont échoué -> il peut vraiment y
        // avoir un problème de compte / de lien cassé, on vérifie côté serveur.
        val playlist = activePlaylist ?: run {
            Toast.makeText(this, "Erreur de lecture. Vérifiez votre connexion.", Toast.LENGTH_LONG).show()
            return
        }
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
                    Toast.makeText(this@PlayerActivity,
                        "Playlist mise à jour, nouvelle tentative…", Toast.LENGTH_SHORT).show()
                    playStreamInternal(updated.streamUrl, updated.name)
                } else {
                    showErrorOverlayAndScheduleBackgroundRetry()
                }
            } else {
                showErrorOverlayAndScheduleBackgroundRetry()
            }
        }
    }

    /**
     * Affiche l'overlay d'erreur (visible, avec bouton "Réessayer maintenant")
     * et programme des tentatives automatiques silencieuses en arrière-plan -
     * voir le commentaire sur [backgroundRetryHandler] pour le contexte.
     */
    private fun showErrorOverlayAndScheduleBackgroundRetry() {
        if (isFinishing) return
        val alreadyShowing = binding.errorOverlay.visibility == View.VISIBLE
        binding.errorOverlay.visibility = View.VISIBLE
        if (!alreadyShowing) backgroundRetryAttempt = 0
        scheduleNextBackgroundRetry()
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
            setMediaItem(MediaItem.fromUri(url))
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
        cancelBackgroundRetry()
        player?.release()
        player = null
        releaseAllPreloads()
    }
}
