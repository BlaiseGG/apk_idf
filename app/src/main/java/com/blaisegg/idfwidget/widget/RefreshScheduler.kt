package com.blaisegg.idfwidget.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Stratégie de rafraîchissement économe en batterie :
 *  1. Périodique toutes les 15 min (minimum Android), uniquement avec réseau,
 *     regroupé par le système en mode Doze, et ignoré si l'écran est éteint.
 *  2. Rafraîchissement immédiat au toucher du widget.
 *  3. "Suivi rapproché" après un toucher : quelques mises à jour à ~1 min d'intervalle,
 *     le temps que l'utilisateur regarde son écran, puis arrêt automatique.
 */
object RefreshScheduler {
    private const val PERIODIC = "passages-periodic"
    private const val NOW = "passages-now"
    private const val FOLLOW_UP = "passages-follow-up"
    const val FOLLOW_UP_COUNT = 5

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(RefreshWorker.KEY_PERIODIC to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(FOLLOW_UP)
        }
    }

    /** Rafraîchit tout de suite (un widget précis, ou tous si [appWidgetId] est null). */
    fun refreshNow(context: Context, appWidgetId: Int? = null, followUps: Int = 0) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(
                workDataOf(
                    RefreshWorker.KEY_WIDGET_ID to (appWidgetId ?: -1),
                    RefreshWorker.KEY_FOLLOW_UPS to followUps,
                ),
            )
            .build()
        val name = if (appWidgetId != null) "$NOW-$appWidgetId" else NOW
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleFollowUp(context: Context, appWidgetId: Int, remaining: Int) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    RefreshWorker.KEY_WIDGET_ID to appWidgetId,
                    RefreshWorker.KEY_FOLLOW_UPS to remaining,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$FOLLOW_UP-$appWidgetId", ExistingWorkPolicy.REPLACE, request)
    }
}
