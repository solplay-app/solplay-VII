package com.solplay.iptv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Point d'entrée de l'application : programme la tâche périodique qui
 * rappelle chaque heure à l'utilisateur le temps restant sur son essai
 * gratuit ou son abonnement Pro, et garde trace de l'activité au premier
 * plan (nécessaire à TvNotificationBanner pour savoir où afficher le
 * bandeau de notification sur TV/Box).
 */
class SolPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = TvNotificationBanner.onActivityResumed(activity)
            override fun onActivityPaused(activity: Activity) = TvNotificationBanner.onActivityPaused(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        scheduleHourlyReminder()
    }

    private fun scheduleHourlyReminder() {
        // 1 heure = intervalle minimum supporté nativement par WorkManager
        // pour les tâches périodiques, ce qui correspond exactement au besoin.
        val request = PeriodicWorkRequestBuilder<RemainingTimeReminderWorker>(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RemainingTimeReminderWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
