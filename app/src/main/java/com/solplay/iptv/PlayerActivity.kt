package com.solplay.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_NAME = "extra_stream_name"

        /** Durée d'affichage du bandeau orange avant qu'il ne disparaisse automatiquement. */
        private const val TITLE_DISPLAY_MS = 5000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var sideAdapter: ChannelAdapter
    private var sideChannels: List<Channel> = emptyList()
    private var activePlaylist: SavedPlaylist? = null
    private var programInfoJob: Job? = null

    private val hideHandler = Handler(Looper.getMainLooper())

    // Le bandeau du titre ET le bouton "Chaînes" apparaissent/disparaissent toujours ensemble :
    // visibles 5 secondes puis masqués automatiquement, et réaffichés sur simple tap écran.
    private val hideControlsRunnable = Runnable {
        binding.tvChannelTitle.visibility = View.GONE
        binding.tvProgramInfo.visibility = View.GONE
        if (sideChannels.size > 1) binding.btnChannelList.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val startUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return
        val startName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
        }

        setupSidePanel()
        playStream(startUrl, startName)

        // Un tap sur l'écran (en dehors des contrôles ExoPlayer) refait apparaître
        // le titre et le bouton "Chaînes" ensemble, pour 5 secondes.
        binding.playerView.setOnClickListener { showControlsTemporarily() }

        binding.btnChannelList.setOnClickListener { toggleSidePanel() }
        setupSideSearch()
    }

    /** Construit le panneau latéral avec la liste de chaînes transmise par ChannelsActivity. */
    private fun setupSidePanel() {
        sideChannels = ChannelRepository.playingList
        activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }
        // Layout "dark" : texte blanc, lisible sur le fond transparent qui laisse voir la vidéo.
        sideAdapter = ChannelAdapter(sideChannels, itemLayoutRes = R.layout.item_channel_dark, epgPlaylist = activePlaylist) { channel ->
            playStream(channel.streamUrl, channel.name)
            binding.channelListPanel.visibility = View.GONE
            binding.etSideSearch.text?.clear()
        }
        binding.recyclerSideChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerSideChannels.adapter = sideAdapter

        // On ne montre le bouton "Chaînes" que s'il y a effectivement d'autres chaînes
        // à proposer (évite un bouton inutile si on arrive d'ailleurs sans contexte).
        binding.btnChannelList.visibility = if (sideChannels.size > 1) View.VISIBLE else View.GONE
    }

    /** Filtre la liste du panneau latéral par nom de chaîne, en direct pendant la saisie. */
    private fun setupSideSearch() {
        binding.etSideSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                val filtered = if (query.isEmpty()) {
                    sideChannels
                } else {
                    sideChannels.filter { it.name.contains(query, ignoreCase = true) }
                }
                sideAdapter.updateData(filtered)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun toggleSidePanel() {
        val opening = binding.channelListPanel.visibility != View.VISIBLE
        binding.channelListPanel.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) {
            // Le panneau étant ouvert, on garde titre + bouton visibles et on suspend
            // la disparition automatique pendant que l'utilisateur cherche/parcourt.
            showControlsTemporarily(keepVisible = true)
        } else {
            binding.etSideSearch.text?.clear()
            showControlsTemporarily()
        }
    }

    /** Change (ou démarre) le flux en cours de lecture, sans recréer l'Activity. */
    private fun playStream(url: String, name: String) {
        binding.tvChannelTitle.text = name
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        loadProgramInfo(url)
        showControlsTemporarily()
    }

    /** Récupère et affiche le programme en cours (EPG) pour la chaîne en cours de lecture. */
    private fun loadProgramInfo(streamUrl: String) {
        programInfoJob?.cancel()
        binding.tvProgramInfo.visibility = View.GONE

        val playlist = activePlaylist ?: return
        val isLive = Channel(name = "", logoUrl = null, groupTitle = null, streamUrl = streamUrl).contentType() == ContentType.LIVE
        if (!isLive) return
        val streamId = XtreamApiClient.extractStreamId(streamUrl)
        if (streamId <= 0) return

        programInfoJob = lifecycleScope.launch {
            val program = XtreamApiClient.fetchNowPlaying(playlist, streamId) ?: return@launch
            binding.tvProgramInfo.text = "${program.startTime}-${program.endTime} · ${program.title}"
            if (binding.tvChannelTitle.visibility == View.VISIBLE) {
                binding.tvProgramInfo.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Affiche ensemble le bandeau du titre et le bouton "Chaînes".
     * Si [keepVisible] est vrai (panneau latéral ouvert), on ne programme pas leur disparition
     * automatique tant que l'utilisateur interagit avec la liste/recherche.
     */
    private fun showControlsTemporarily(keepVisible: Boolean = false) {
        binding.tvChannelTitle.visibility = View.VISIBLE
        if (binding.tvProgramInfo.text.isNotEmpty()) binding.tvProgramInfo.visibility = View.VISIBLE
        if (sideChannels.size > 1) binding.btnChannelList.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (!keepVisible) {
            hideHandler.postDelayed(hideControlsRunnable, TITLE_DISPLAY_MS)
        }
    }

    override fun onStop() {
        super.onStop()
        hideHandler.removeCallbacks(hideControlsRunnable)
        player?.release()
        player = null
    }
}
