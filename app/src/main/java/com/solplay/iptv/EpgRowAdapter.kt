package com.solplay.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Une ligne = une chaîne : logo + nom (colonne fixe) puis la frise de
 * programmes (colonne défilable, synchronisée avec l'en-tête des heures et
 * les autres lignes via [getScrollX]/[onRowScrolled] - voir EpgGridActivity).
 */
class EpgRowAdapter(
    private val channels: List<Channel>,
    private val playlist: SavedPlaylist,
    private val windowStart: Long,
    private val windowEnd: Long,
    private val pxPerMinute: Float,
    private val getScrollX: () -> Int,
    private val onRowScrolled: (Int) -> Unit
) : RecyclerView.Adapter<EpgRowAdapter.RowViewHolder>() {

    // Un scope par adapter pour les récupérations EPG en tâche de fond,
    // annulé quand l'écran est fermé (voir onDetachedFromRecyclerView).
    private val adapterScope = CoroutineScope(Dispatchers.Main)

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.ivEpgLogo)
        val name: TextView = view.findViewById(R.id.tvEpgChannelName)
        val scroll: SyncHorizontalScrollView = view.findViewById(R.id.scrollRow)
        val timeline: LinearLayout = view.findViewById(R.id.llTimeline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val channel = channels[position]
        // Sert à vérifier, après l'appel réseau, que ce ViewHolder affiche
        // toujours cette chaîne (il a pu être recyclé pendant l'attente).
        holder.itemView.tag = channel

        holder.name.text = channel.name
        holder.logo.setImageResource(R.drawable.ic_channel_placeholder)
        if (!channel.logoUrl.isNullOrEmpty()) {
            holder.logo.load(channel.logoUrl, ImageLoader.get(holder.itemView.context)) {
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
        }

        // Affiche un espace réservé le temps que la vraie grille arrive,
        // pour que la ligne ait tout de suite la bonne largeur totale
        // (et donc un défilement horizontal cohérent avec les autres lignes).
        renderSegments(holder, listOf(EpgGridUtils.Segment("Chargement…", windowStart, windowEnd, isPlaceholder = true)))
        holder.scroll.scrollTo(getScrollX(), 0)
        holder.scroll.onScrollXChanged = { x -> onRowScrolled(x) }

        val streamId = XtreamApiClient.extractStreamId(channel.streamUrl)
        if (streamId <= 0) {
            renderSegments(holder, listOf(EpgGridUtils.Segment(EpgGridUtils.NO_INFO_LABEL, windowStart, windowEnd, isPlaceholder = true)))
            return
        }

        adapterScope.launch {
            val slots = XtreamApiClient.fetchProgramSlotsRaw(playlist, streamId, limit = 30)
            // La vue a pu être recyclée pour une autre chaîne pendant l'appel réseau.
            if (holder.itemView.tag != channel) return@launch
            val segments = EpgGridUtils.buildSegments(slots, windowStart, windowEnd)
            renderSegments(
                holder,
                segments.ifEmpty { listOf(EpgGridUtils.Segment(EpgGridUtils.NO_INFO_LABEL, windowStart, windowEnd, isPlaceholder = true)) }
            )
            holder.scroll.scrollTo(getScrollX(), 0)
        }
    }

    private fun renderSegments(holder: RowViewHolder, segments: List<EpgGridUtils.Segment>) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val minWidthPx = (40 * density).toInt()

        holder.timeline.removeAllViews()
        for (segment in segments) {
            val durationMinutes = (segment.endMillis - segment.startMillis) / 60000.0
            val widthPx = maxOf((durationMinutes * pxPerMinute).toInt(), minWidthPx)

            val cell = TextView(context)
            cell.layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
            cell.text = segment.title
            cell.maxLines = 2
            cell.ellipsize = android.text.TextUtils.TruncateAt.END
            cell.textSize = 12f
            cell.setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            cell.gravity = android.view.Gravity.CENTER_VERTICAL
            if (segment.isPlaceholder) {
                cell.setBackgroundResource(R.drawable.bg_epg_cell_placeholder)
                cell.setTextColor(android.graphics.Color.parseColor("#8A8A8A"))
            } else {
                cell.setBackgroundResource(R.drawable.bg_epg_cell)
                cell.setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
            }
            holder.timeline.addView(cell)
        }
    }

    override fun getItemCount(): Int = channels.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.coroutineContext.cancelChildren()
    }
}
