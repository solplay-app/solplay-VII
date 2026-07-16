package com.solplay.iptv

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * HorizontalScrollView qui notifie [onScrollXChanged] à chaque changement de
 * position, pour synchroniser le défilement horizontal de la grille EPG :
 * l'en-tête des heures et chaque ligne de chaîne utilisent une instance de
 * cette vue, et EpgGridActivity relaie la position de l'une vers toutes les
 * autres (voir EpgGridActivity.kt).
 *
 * On surcharge onScrollChanged (disponible depuis l'API 1) plutôt que
 * setOnScrollChangeListener (API 23+ seulement) car minSdk = 21 ici.
 */
class SyncHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onScrollXChanged: ((Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollXChanged?.invoke(l)
    }
}
