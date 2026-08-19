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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.solplay.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran d'accueil principal SOLPLAY.
 *
 * - En portrait : présentation type smartphone, fidèle à la maquette fournie.
 * - En paysage : présentation type TV / box Android, fidèle à la maquette fournie.
 */
class HomeActivity : AppCompatActivity() {

    companion object {
        /** Au-delà de cette ancienneté, le cache est rafraîchi en arrière-plan à l'ouverture.
         *  CORRECTIF (URL périmées) : abaissé de 30 min à 10 min pour renouveler
         *  plus souvent les liens de flux (les tokens Xtream/M3U expirent vite) et
         *  éviter de repartir sur des URL mortes après un redémarrage. */
        private const val CACHE_REFRESH_THRESHOLD_MS = 10 * 60 * 1000L // 10 min
    }

    private lateinit var binding: ActivityHomeBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val heroHandler = Handler(Looper.getMainLooper())
    private val heroSwapDuration = 250L
    private val heroInterval = 5_000L
    private val heroResumeDelay = 10_000L
    private var heroItems: List<Pair<Channel, String>> = emptyList()
    private var heroIndex = 0
    private var heroPaused = false
    private var heroSwapPending = false
    private var heroShowingNext = false
    private val heroRotation = object : Runnable { override fun run() { if (!heroPaused) showNextHero(); heroHandler.postDelayed(this, heroInterval) } }
    private val heroResume = Runnable { heroPaused = false; heroHandler.removeCallbacks(heroRotation); heroHandler.postDelayed(heroRotation, heroInterval) }

    /** Remet l'horloge de la barre du haut à jour toutes les minutes tant que l'écran est visible. */
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

        binding.tileLiveTv.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.tileMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.tileSeries.setOnClickListener { openChannels(ContentType.SERIES) }

        // Catégories de la nouvelle maquette — même handlers que les tuiles
        // latérales correspondantes, le clic utilisateur appuie sur l'une ou
        // l'autre suivant le contexte (télécommande vs télécommande TV avec
        // pad directionnel).
        binding.cardLive?.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.cardMovies?.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.cardSeries?.setOnClickListener { openChannels(ContentType.SERIES) }

        // CTA du panneau vedette : "Lecture" lance le hero courant, "Ma liste"
        // ajoute/retire le hero courant des favoris. Lecture délègue à la
        // même logique que la rangée d'affiches si un hero est défini.

        // Pilules vertes : UPDATE EPG → rafraîchissement manuel,
        // ACCOUNT → écran "À propos", CATCH UP → raccourci replay.
        binding.pillUpdateEpg?.setOnClickListener { refreshEpgAndChannels() }
        binding.pillAccount?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.pillCatchUp?.setOnClickListener { openCatchupShortcut() }

        // Boutons secondaires visibles sur les maquettes.
        binding.tileChangeServer.setOnClickListener { refreshEpgAndChannels() }
        binding.tileFavorites.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.tileHistory.setOnClickListener { openCatchupShortcut() }

        // Icônes TV en haut à droite.
        binding.tileAccount.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.tileSettings.setOnClickListener {
            startActivity(Intent(this, PlaylistsListActivity::class.java))
        }

        // Tuile Reprendre (optionnelle, hors maquette mais utile si présente).
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
        setupHomePosterRow()
        setupFeaturedDefaults()
        binding.heroContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) pauseHeroRotation(true)
            false
        }
        binding.btnHeroPlay?.setOnClickListener { pauseHeroRotation(true); playFeaturedIfAny() }
        binding.btnHeroMyList?.setOnClickListener { pauseHeroRotation(true); toggleFeaturedFavorite() }
    }

    // ──────────────────────────────────────────────────────────
    // Bannière héro + rangée d'affiches (Films) de l'écran d'accueil
    // ──────────────────────────────────────────────────────────

    /** Nombre d'affiches chargées dans la rangée d'accueil (au-delà, l'utilisateur passe par "Films"). */
    private val homeRowLimit = 25

    private var homePosterAdapter: ChannelAdapter? = null

    private fun setupHomePosterRow() {
        val movies = ChannelRepository.channels.filter { it.contentType() == ContentType.MOVIE }.take(homeRowLimit)
        if (movies.isEmpty()) {
            binding.tvPosterSectionLabel?.visibility = View.GONE
            binding.recyclerHomePosters?.visibility = View.GONE
            return
        }
        binding.tvPosterSectionLabel?.visibility = View.VISIBLE
        binding.recyclerHomePosters?.visibility = View.VISIBLE
        binding.recyclerHomePosters?.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val adapter = ChannelAdapter(movies, itemLayoutRes = R.layout.item_home_poster) { channel -> playFromHome(channel) }
        homePosterAdapter = adapter
        binding.recyclerHomePosters?.adapter = adapter
        lifecycleScope.launch {
            val resolved = movies.mapNotNull { channel ->
                val url = channel.logoUrl?.takeIf { it.isNotBlank() } ?: run {
                    val result = withContext(Dispatchers.IO) { TmdbClient.searchMovie(channel.name) }
                    result.info?.posterUrl
                }
                if (!url.isNullOrBlank()) channel to url else null
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

    private fun setupHeroBanner(channel: Channel) { }

    private fun showHeroImage(url: String, first: Boolean = false) {
        if (heroSwapPending) return
        heroSwapPending = true
        val incoming = if (heroShowingNext) binding.ivHeroBackdrop else binding.ivHeroBackdropNext
        val outgoing = if (heroShowingNext) binding.ivHeroBackdropNext else binding.ivHeroBackdrop
        incoming.alpha = 0f
        incoming.load(url, ImageLoader.get(this)) {
            placeholder(null)
            error(null)
            listener(onSuccess = { _, result ->
                heroSwapPending = false
                val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmap != null) updateHeroBackground(bitmap)
                incoming.animate().alpha(1f).setDuration(if (first) heroSwapDuration else heroSwapDuration).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                if (!first) outgoing.animate().alpha(0f).setDuration(heroSwapDuration).start()
                else outgoing.alpha = 0f
                heroShowingNext = !heroShowingNext
            }, onError = { _, _ -> heroSwapPending = false })
        }
    }

    private fun updateHeroBackground(bitmap: Bitmap) {
        val sample = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val c = sample.getPixel(0, 0)
        sample.recycle()
        val dark = Color.rgb((Color.red(c) * .20f).toInt(), (Color.green(c) * .20f).toInt(), (Color.blue(c) * .20f).toInt())
        val drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(2, 7, 13), dark, Color.rgb(1, 3, 7)))
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

    /** Lance la lecture d'un film choisi directement depuis la rangée d'accueil. */
    private fun playFromHome(channel: Channel) {
        if (ParentalControl.isAdultChannel(channel) && !ParentalControl.isUnlocked()) {
            showHomeParentalPinDialog { playFromHome(channel) }
            return
        }
        ChannelRepository.setPlayingList(homePosterAdapter?.currentList() ?: listOf(channel))
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
        startActivity(intent)
    }

    /**
     * Boutons CTA du panneau vedette — branchés sur les mêmes données que la
     * rangée d'affiches. Tant qu'aucune playlist n'est chargée (premier
     * lancement, cache froid), on laisse juste un feedback utilisateur.
     */
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

    /**
     * Renseigne les champs statiques du nouveau panneau vedette quand il n'y a
     * pas encore de film TMDB résolu (placeholder plutôt qu'écran vide).
     */
    private fun setupFeaturedDefaults() {
        binding.tvPosterSectionLabel?.text = "🔥 SELECTION DU JOUR - TOP FILMS"
    }

    private fun showHomeParentalPinDialog(onGranted: () -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
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

    /**
     * Raccourci "Catch Up" : l'utilisateur ouvre directement la section Live,
     * puis pourra lancer le replay depuis le lecteur si son fournisseur le propose.
     */
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

    /** Actualisation manuelle des chaînes / catégories / EPG. */
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
                Toast.makeText(
                    this@HomeActivity,
                    "Mise à jour terminée.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Affiche les informations visibles en bas de l'écran comme sur la maquette.
     */
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

    /**
     * Rafraîchit silencieusement la playlist active en arrière-plan si le cache
     * commence à dater.
     */
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
