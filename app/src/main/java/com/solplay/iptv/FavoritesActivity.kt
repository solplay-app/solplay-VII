package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Liste des chaînes/films/séries marqués en favori (voir [FavoritesStore]).
 *
 * Avant ce fichier, la tuile "⭐ Mes favoris" de l'accueil ouvrait
 * [AboutActivity] à la place (mauvais branchement, aucun rapport) : il n'y
 * avait tout simplement aucun écran pour consulter ses favoris, alors même
 * que le bouton "marquer en favori" existe depuis [DetailActivity] et
 * [PlayerActivity].
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerFavorites)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ChannelAdapter(
            channels = emptyList(),
            onLongClick = { channel -> confirmRemove(channel) },
            onClick = { channel -> openPlayer(channel) }
        )
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val favorites = FavoritesStore.getAll(this)
        adapter.updateData(favorites)
        findViewById<View>(R.id.tvEmpty).visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmRemove(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle("Retirer des favoris ?")
            .setMessage(channel.name)
            .setPositiveButton("Retirer") { _, _ ->
                FavoritesStore.toggle(this, channel)
                Toast.makeText(this, "Retiré des favoris", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openPlayer(channel: Channel) {
        if (ParentalControl.isAdultChannel(channel) && !ParentalControl.isUnlocked()) {
            Toast.makeText(this, "Contenu verrouillé (contrôle parental).", Toast.LENGTH_LONG).show()
            return
        }
        ChannelRepository.setPlayingList(adapter.currentList())
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
        startActivity(intent)
    }
}
