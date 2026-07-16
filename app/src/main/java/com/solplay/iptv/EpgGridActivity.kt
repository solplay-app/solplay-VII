package com.solplay.iptv

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityEpgGridBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Grille EPG façon "zappeur" : toutes les chaînes affichées en même temps
 * sur une frise horaire commune (comme IPTV Smarters / capture de
 * référence), avec défilement horizontal synchronisé entre l'en-tête des
 * heures et chaque ligne de chaîne.
 *
 * Les chaînes à afficher et la playlist active sont déposées dans
 * [ChannelRepository]/[PlaylistStore] avant l'ouverture de cet écran (voir
 * ChannelsActivity.openEpgGrid) plutôt que passées par Intent, pour la même
 * raison que le reste de l'app (limite de taille du Binder).
 */
class EpgGridActivity : AppCompatActivity() {

    companion object {
        private const val WINDOW_HOURS = 3
        private const val SLOT_MINUTES = 30
        private const val SLOT_WIDTH_DP = 110
    }

    private lateinit var binding: ActivityEpgGridBinding
    private var adapter: EpgRowAdapter? = null
    private var currentScrollX = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val channels = ChannelRepository.epgGridChannels
        val playlist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }

        if (playlist == null || channels.isEmpty()) {
            binding.tvEpgEmpty.visibility = android.view.View.VISIBLE
            binding.recyclerEpgRows.visibility = android.view.View.GONE
            return
        }

        val density = resources.displayMetrics.density
        val slotWidthPx = (SLOT_WIDTH_DP * density).toInt()
        val pxPerMinute = slotWidthPx.toFloat() / SLOT_MINUTES

        // Fenêtre affichée : de maintenant (arrondi à la demi-heure précédente)
        // à +WINDOW_HOURS. Correspond à ce que montre l'app de référence
        // (quelques créneaux de 30 min autour de l'heure actuelle).
        val calendar = Calendar.getInstance()
        val minute = calendar.get(Calendar.MINUTE)
        calendar.set(Calendar.MINUTE, if (minute < 30) 0 else 30)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val windowStart = calendar.timeInMillis
        val windowEnd = windowStart + WINDOW_HOURS * 60 * 60 * 1000L

        buildHeaderTicks(windowStart, windowEnd, slotWidthPx)

        // Synchronisation du défilement horizontal :
        // - l'en-tête pilote toutes les lignes actuellement visibles
        // - chaque ligne (glissée directement) pilote l'en-tête + les autres lignes
        // La vérification "si déjà à cette position" évite toute boucle infinie.
        binding.scrollHeader.onScrollXChanged = { x ->
            currentScrollX = x
            propagateScrollToRows(x)
        }

        adapter = EpgRowAdapter(
            channels = channels,
            playlist = playlist,
            windowStart = windowStart,
            windowEnd = windowEnd,
            pxPerMinute = pxPerMinute,
            getScrollX = { currentScrollX },
            onRowScrolled = { x ->
                currentScrollX = x
                if (binding.scrollHeader.scrollX != x) binding.scrollHeader.scrollTo(x, 0)
                propagateScrollToRows(x)
            }
        )
        binding.recyclerEpgRows.layoutManager = LinearLayoutManager(this)
        binding.recyclerEpgRows.adapter = adapter
    }

    /** Applique la position de défilement horizontale à toutes les lignes actuellement visibles/recyclées. */
    private fun propagateScrollToRows(x: Int) {
        for (i in 0 until binding.recyclerEpgRows.childCount) {
            val row = binding.recyclerEpgRows.getChildAt(i)
            val rowScroll = row.findViewById<SyncHorizontalScrollView>(R.id.scrollRow)
            if (rowScroll.scrollX != x) rowScroll.scrollTo(x, 0)
        }
    }

    private fun buildHeaderTicks(windowStart: Long, windowEnd: Long, slotWidthPx: Int) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.llHeaderTicks.removeAllViews()
        var t = windowStart
        while (t < windowEnd) {
            val tick = layoutInflater.inflate(R.layout.item_epg_tick, binding.llHeaderTicks, false) as TextView
            tick.text = sdf.format(Date(t))
            tick.layoutParams = LinearLayout.LayoutParams(slotWidthPx, ViewGroup.LayoutParams.MATCH_PARENT)
            binding.llHeaderTicks.addView(tick)
            t += SLOT_MINUTES * 60 * 1000L
        }
    }
}
