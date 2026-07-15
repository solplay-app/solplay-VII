package com.solplay.iptv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        TrialManager.ensureFirstLaunchRecorded(this)

        // Lancée en parallèle du délai d'affichage du splash : ne retarde
        // jamais le démarrage de l'app si le réseau est lent ou absent.
        checkForUpdate()

        Handler(Looper.getMainLooper()).postDelayed({
            goToNextScreen()
        }, 1500)
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            if (update != null && !isFinishing) {
                showUpdateDialog(update)
            }
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, update.versionName))
            .setCancelable(false)
            .setPositiveButton(R.string.update_download) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                startActivity(intent)
                goToNextScreen()
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                goToNextScreen()
            }
            .show()
    }

    private fun goToNextScreen() {
        if (navigated) return
        navigated = true

        if (!TrialManager.canAccessApp(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        // Comme les autres lecteurs IPTV, on "reste connecté" : si une
        // playlist active a déjà été chargée avec succès, on rouvre
        // directement sur l'accueil avec les chaînes en cache (instantané),
        // au lieu de retélécharger toute la playlist et de repasser par
        // l'écran de connexion à chaque lancement. HomeActivity se charge
        // ensuite de rafraîchir en arrière-plan si le cache commence à dater.
        val activeId = PlaylistStore.getActiveId(this)
        val activePlaylist = activeId?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }
        val cached = activePlaylist?.let { ChannelCacheStore.load(this, it.id) }

        val next = if (activePlaylist != null && cached != null) {
            ChannelRepository.setChannels(cached)
            HomeActivity::class.java
        } else {
            PlaylistActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
