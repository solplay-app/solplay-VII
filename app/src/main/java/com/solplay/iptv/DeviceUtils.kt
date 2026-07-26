package com.solplay.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Détection "appareil TV / Box" - utilisée notamment pour adapter le contact
 * revendeur : sur téléphone, on ouvre directement WhatsApp ; sur TV/Box, où
 * WhatsApp n'est généralement pas installé et où la télécommande ne permet
 * pas de taper/naviguer facilement, on affiche un QR code à scanner avec un
 * téléphone à la place (voir LicenseActivity.openWhatsAppContact).
 *
 * Combine plusieurs signaux car aucun seul n'est fiable à 100% sur tous les
 * boîtiers Android TV/box génériques (certains ne se déclarent pas comme
 * "television" alors qu'ils n'ont ni écran tactile ni app WhatsApp) :
 * - UI_MODE_TYPE_TELEVISION : mode TV standard Android
 * - FEATURE_LEANBACK : appareils Android TV certifiés Google
 * - absence de FEATURE_TOUCHSCREEN : beaucoup de box génériques (pilotées à
 *   la télécommande/souris) ne déclarent pas d'écran tactile
 */
object DeviceUtils {

    fun isTvDevice(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isUiModeTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val isLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasNoTouchscreen = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return isUiModeTv || isLeanback || hasNoTouchscreen
    }
}
