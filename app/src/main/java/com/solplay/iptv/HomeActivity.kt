package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.solplay.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val CACHE_REFRESH_THRESHOLD_MS = 10 * 60 * 1000L
    }

    private lateinit var binding: ActivityHomeBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val heroHandler = Handler(Looper.getMainLooper())
    /** Durée du CROSSFADE entre miniatures (hero) — fondu fluide */
    private val heroSwapDuration = 600L
    private val heroInterval = 5_000L
    private val heroResumeDelay = 10_000L
    private var heroItems: List<Pair<Channel, String>> = emptyList()
    private var heroIndex = 0
    private var heroPaused = false
    private var heroSwapPending = false
    private var heroShowingNext = false
    private val heroRotation = object : Runnable {
        override fun run() {
            if (!heroPaused) showNextHero()
            heroHandler.postDelayed(this, heroInterval)
        }
    }
    private val heroResume = Runnable {
        heroPaused = false
        heroHandler.removeCallbacks(heroRotation)
        heroHandler.postDelayed(heroRotation, heroInterval)
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvClock.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PlaylistStore.getActiveId(this) == null) {
            ChannelRepository.clear()
            startActivity(Intent(this, PlaylistActivity::class.java))
            finish()
            return
        }
        clockHandler.post(clockRunnable)
        showAccountInfo()
        if (heroItems.isNotEmpty()) resumeHeroRotation()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        pauseHeroRotation(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sidebar (rangées complètes icône+libellé).
        binding.rowLive.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.rowMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.rowSeries.setOnClickListener { openChannels(ContentType.SERIES) }
        binding.rowAccount.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.rowRefresh.setOnClickListener { refreshEpgAndChannels() }
        // Filets de sécurité : clic direct sur l'icône interne.
        binding.tileLiveTv.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.tileMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.tileSeries.setOnClickListener { openChannels(ContentType.SERIES) }

        // Pilules CTA du panneau vedette.
        binding.pillUpdateEpg?.setOnClickListener { refreshEpgAndChannels() }
        binding.pillAccount?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.pillCatchUp?.setOnClickListener { openCatchupShortcut() }

        // Icônes en haut à droite.
        binding.tileFavorites.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.tileHistory.setOnClickListener { openCatchupShortcut() }
        binding.tileSettings.setOnClickListener {
            startActivity(Intent(this, PlaylistsListActivity::class.java))
        }

        val resume = ResumeStore.get(this)
        if (resume != null) {
            binding.tileResume.visibility = View.VISIBLE
            binding.tvResumeLabel.text = "▶ ${resume.name}"
            binding.tileResume.setOnClickListener {
                val ch = Channel(
                    name = resume.name,
                    logoUrl = resume.logoUrl,
                    groupTitle = null,
                    streamUrl = resume.streamUrl
                )
                ChannelRepository.setPlayingList(listOf(ch))
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, resume.streamUrl)
                    putExtra(PlayerActivity.EXTRA_STREAM_NAME, resume.name)
                    if (!resume.isLive) putExtra(PlayerActivity.EXTRA_RESUME_POS, resume.positionMs)
                }
                startActivity(intent)
            }
        } else {
            binding.tileResume.visibility = View.GONE
        }

        showAccountInfo()
        refreshCacheInBackgroundIfStale()
        setupHomePosterRows()
        setupFeaturedDefaults()
        binding.heroContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) pauseHeroRotation(true)
            false
        }
        binding.btnHeroPlay?.setOnClickListener { pauseHeroRotation(true); playFeaturedIfAny() }
        binding.btnHeroMyList?.setOnClickListener { pauseHeroRotation(true); toggleFeaturedFavorite() }
    }

    /** Limite par rangée : 4 colonnes × 2 lignes = 8 tuiles max par rangée (conforme maquette). */
    private val homeRowLimit = 16

    private var homePosterAdapterTop: ChannelAdapter? = null

    /**
     * Configure les DEUX rangées de tuiles de l'écran d'accueil, réservées aux
     * Films (span=2 sur un seul RecyclerView horizontal → 2 rangées). Les
     * Séries ne sont plus dupliquées ici ; elles restent accessibles via le
     * menu latéral SERIES → ChannelsActivity.
     * Le sélecteur par défaut (1ère tuile sélectionnée) rend le sélecteur visible dès l'ouverture.
     */
    private fun setupHomePosterRows() {
        val movies = ChannelRepository.channels
            .filter { it.contentType() == ContentType.MOVIE }.take(homeRowLimit)

        // Rangée 1 : FILMS.
        if (movies.isNotEmpty()) {
            binding.tvPosterSectionLabel.visibility = View.VISIBLE
            binding.recyclerHomePostersTop.visibility = View.VISIBLE
            // DEUX rangées propres de vignettes (spanCount = nombre de RANGÉES en
            // orientation HORIZONTAL). L'ancien code utilisait spanCount=4, ce qui
            // empilait 4 rangées dans la hauteur prévue pour 1 et écrasait chaque
            // affiche à 1/4 de sa hauteur ("bandes/stores vénitiens"). spanCount=2
            // donne deux rangées bien proportionnées, conforme à la demande.
            val gridMovies = GridLayoutManager(this, 2, RecyclerView.HORIZONTAL, false)
            binding.recyclerHomePostersTop.layoutManager = gridMovies
            val adapterMovies = ChannelAdapter(movies, itemLayoutRes = R.layout.item_home_poster) { ch -> playFromHome(ch) }
            homePosterAdapterTop = adapterMovies
            binding.recyclerHomePostersTop.adapter = adapterMovies
            // Focus initial sur la 1ère tuile du 1er groupe : sélecteur visible.
            binding.recyclerHomePostersTop.post {
                binding.recyclerHomePostersTop.findViewHolderForLayoutPosition(0)?.itemView?.requestFocus()
            }
        } else {
            binding.recyclerHomePostersTop.visibility = View.GONE
        }

        // Rangée Séries retirée de l'accueil (accessible via le menu latéral SERIES) :
        // l'écran d'accueil garde uniquement les DEUX rangées de Films.
        binding.recyclerHomePostersBot.visibility = View.GONE

        // Le hero (miniature rotative du haut) utilise les FILMS.
        //
        // CORRECTIF "zone toujours noire" : l'ancien code résolvait les affiches
        // TMDB une par une, en SÉRIE (chaque appel réseau attendait le précédent).
        // Avec jusqu'à 16 films, une seule requête lente/qui traîne bloquait tout
        // le lot, et heroItems n'était renseigné qu'une fois LA TOTALITÉ des appels
        // terminés → le hero pouvait rester noir très longtemps, voire indéfiniment
        // en cas de requête bloquée. On lance maintenant les recherches TMDB EN
        // PARALLÈLE (async/awaitAll) et on affiche la miniature dès que la première
        // affiche valide est disponible, sans attendre les autres.
        lifecycleScope.launch {
            val heroCandidates = movies.take(heroMaxItems)
            val resolved = coroutineScope {
                heroCandidates.map { channel ->
                    async(Dispatchers.IO) {
                        val url = channel.logoUrl?.takeIf { it.isNotBlank() }
                            ?: TmdbClient.searchMovie(channel.name).info?.posterUrl
                        if (!url.isNullOrBlank()) channel to url else null
                    }
                }.awaitAll().filterNotNull()
            }
            if (isFinishing || isDestroyed) return@launch
            heroItems = resolved
            if (heroItems.isNotEmpty()) {
                heroIndex = 0
                showHeroImage(heroItems[0].second, first = true)
                resumeHeroRotation()
            }
        }
    }

    /** Nombre de films dont on résout l'affiche pour la rotation du hero
     *  (inutile d'interroger TMDB pour les 16 tuiles de la grille : le hero
     *  n'affiche qu'un sous-ensemble en rotation). */
    private val heroMaxItems = 8

    /**
     * CROSSFADE fluide entre miniatures hérö :
     * - Utilise deux ImageView (current + next) empilés.
     * - Télécharge la prochaine miniature en alpha=0, puis anime les deux
     *   vers leur cible respective sur heroSwapDuration (600 ms).
     * - Pas de placeholder qui resterait figé : on anime la nouvelle image
     *   immédiatement à l'arrivée du bitmap.
     */
    private fun showHeroImage(url: String, first: Boolean = false) {
        if (heroSwapPending) return
        heroSwapPending = true
        val incoming = if (heroShowingNext) binding.ivHeroBackdrop else binding.ivHeroBackdropNext
        val outgoing = if (heroShowingNext) binding.ivHeroBackdropNext else binding.ivHeroBackdrop

        // Prépare la cible : invisible jusqu'au chargement.
        incoming.alpha = 0f
        incoming.load(url, ImageLoader.get(this)) {
            placeholder(null)
            error(null)
            listener(
                onSuccess = { _, result ->
                    heroSwapPending = false
                    binding.ivHeroPlaceholderLogo.animate().alpha(0f).setDuration(heroSwapDuration).start()
                    val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) updateHeroBackground(bitmap)
                    // CROSSFADE : entrée vers 1.f (durée 600ms) + sortie de l'ancien vers 0.f.
                    incoming.animate()
                        .alpha(1f)
                        .setDuration(heroSwapDuration)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    if (!first) {
                        outgoing.animate()
                            .alpha(0f)
                            .setDuration(heroSwapDuration)
                            .setInterpolator(android.view.animation.AccelerateInterpolator())
                            .start()
                    } else {
                        outgoing.alpha = 0f
                    }
                    heroShowingNext = !heroShowingNext
                },
                onError = { _, _ ->
                    heroSwapPending = false
                    incoming.alpha = if (first) 1f else 0f
                }
            )
        }
    }

    private fun updateHeroBackground(bitmap: Bitmap) {
        val sample = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val c = sample.getPixel(0, 0)
        sample.recycle()
        val dark = Color.rgb(
            (Color.red(c) * .20f).toInt(),
            (Color.green(c) * .20f).toInt(),
            (Color.blue(c) * .20f).toInt()
        )
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(2, 7, 13), dark, Color.rgb(1, 3, 7))
        )
        binding.heroBackdropTint.background = drawable
        binding.heroBackdropTint.animate().alpha(1f).setDuration(450L).start()
    }

    private fun showNextHero() {
        if (heroItems.size < 2 || heroPaused) return
        heroIndex = (heroIndex + 1) % heroItems.size
        showHeroImage(heroItems[heroIndex].second)
    }

    private fun pauseHeroRotation(resumeLater: Boolean) {
        heroPaused = true
        heroHandler.removeCallbacks(heroRotation)
        heroHandler.removeCallbacks(heroResume)
        if (resumeLater) heroHandler.postDelayed(heroResume, heroResumeDelay)
    }

    private fun resumeHeroRotation() {
        heroPaused = false
        heroHandler.removeCallbacks(heroRotation)
        heroHandler.removeCallbacks(heroResume)
        if (heroItems.size > 1) heroHandler.postDelayed(heroRotation, heroInterval)
    }

    private fun playFromHome(channel: Channel) {
        if (ParentalControl.isAdultChannel(channel) && !ParentalControl.isUnlocked()) {
            showHomeParentalPinDialog { playFromHome(channel) }
            return
        }
        val all = homePosterAdapterTop?.currentList().orEmpty()
        ChannelRepository.setPlayingList(all.ifEmpty { listOf(channel) })
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
        startActivity(intent)
    }

    private fun playFeaturedIfAny() {
        val firstMovie = ChannelRepository.channels
            .firstOrNull { it.contentType() == ContentType.MOVIE }
        if (firstMovie == null) {
            Toast.makeText(this, "Aucune playlist chargée pour le moment.", Toast.LENGTH_SHORT).show()
            return
        }
        playFromHome(firstMovie)
    }

    private fun toggleFeaturedFavorite() {
        val firstMovie = ChannelRepository.channels
            .firstOrNull { it.contentType() == ContentType.MOVIE }
        if (firstMovie == null) {
            Toast.makeText(this, "Aucune entrée à ajouter.", Toast.LENGTH_SHORT).show()
            return
        }
        val nowFav = FavoritesStore.toggle(this, firstMovie)
        val msg = if (nowFav) "Ajouté à « Ma liste »" else "Retiré de « Ma liste »"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setupFeaturedDefaults() {
        binding.tvPosterSectionLabel.text = "🔥 SELECTION DU JOUR - TOP FILMS"
    }

    private fun showHomeParentalPinDialog(onGranted: () -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Code parental (4 chiffres)"
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔒 Contenu protégé")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (ParentalControl.verifyPin(this, input.text.toString())) {
                    ParentalControl.unlock()
                    onGranted()
                } else {
                    Toast.makeText(this, "Code incorrect.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openChannels(type: ContentType) {
        val intent = Intent(this, ChannelsActivity::class.java)
        intent.putExtra(ChannelsActivity.EXTRA_INITIAL_TYPE, type.name)
        startActivity(intent)
    }

    private fun openCatchupShortcut() {
        val activeId = PlaylistStore.getActiveId(this) ?: return
        val playlist = PlaylistStore.getAll(this).firstOrNull { it.id == activeId } ?: return
        if (playlist.extractXtreamCredentials() == null) {
            Toast.makeText(
                this,
                "Catch Up disponible uniquement avec une playlist Xtream compatible.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            this,
            "Choisissez une chaîne LIVE puis utilisez Catch Up dans le lecteur.",
            Toast.LENGTH_LONG
        ).show()
        openChannels(ContentType.LIVE)
    }

    private fun refreshEpgAndChannels() {
        val activeId = PlaylistStore.getActiveId(this) ?: return
        val playlist = PlaylistStore.getAll(this).firstOrNull { it.id == activeId } ?: return
        Toast.makeText(this, "Actualisation EPG en cours…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val refreshed = ChannelRefresher.refresh(this@HomeActivity, playlist)
            if (isFinishing) return@launch
            if (refreshed.isNullOrEmpty()) {
                Toast.makeText(
                    this@HomeActivity,
                    "Impossible de mettre à jour l'EPG pour le moment.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@HomeActivity, "Mise à jour terminée.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAccountInfo() {
        val activeId = PlaylistStore.getActiveId(this) ?: return
        val playlist = PlaylistStore.getAll(this).firstOrNull { it.id == activeId } ?: return
        binding.tvConnectedAs.text = "USER: ${playlist.name}"
        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist) ?: return@launch
            val expiresAt = status.expiresAtMillis ?: return@launch
            if (isFinishing) return@launch
            binding.tvExpiration.text = "EXPIRE: ${TrialManager.formatDate(expiresAt)}"
        }
    }

    private fun refreshCacheInBackgroundIfStale() {
        lifecycleScope.launch {
            val activeId = PlaylistStore.getActiveId(this@HomeActivity) ?: return@launch
            val playlist = PlaylistStore.getAll(this@HomeActivity).firstOrNull { it.id == activeId } ?: return@launch
            if (ChannelCacheStore.ageMillis(this@HomeActivity, playlist.id) < CACHE_REFRESH_THRESHOLD_MS) return@launch
            ChannelRefresher.refresh(this@HomeActivity, playlist)
        }
    }

    override fun onDestroy() {
        heroHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
