package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
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
        /** Au-delà de cette ancienneté, le cache est rafraîchi en arrière-plan à l'ouverture. */
        private const val CACHE_REFRESH_THRESHOLD_MS = 30 * 60 * 1000L // 30 min
    }

    private lateinit var binding: ActivityHomeBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileLiveTv.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.tileMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.tileSeries.setOnClickListener { openChannels(ContentType.SERIES) }

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

        binding.tvConnectedAs.text = "Logged in as : ${playlist.name}"

        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist) ?: return@launch
            val expiresAt = status.expiresAtMillis ?: return@launch
            if (isFinishing) return@launch
            binding.tvExpiration.text = "Expiration : ${TrialManager.formatDate(expiresAt)}"
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
}
