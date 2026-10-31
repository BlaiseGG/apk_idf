package com.blaisegg.idfwidget.live

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blaisegg.idfwidget.app
import com.blaisegg.idfwidget.data.PrimException
import com.blaisegg.idfwidget.widget.RefreshScheduler
import kotlinx.coroutines.flow.first

class LiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!LiveTracker.isActive(ctx)) return Result.success()

        if (System.currentTimeMillis() - LiveTracker.startedAt(ctx) > LiveTracker.MAX_DURATION_MS) {
            LiveTracker.stop(ctx) // arrêt automatique pour préserver la batterie
            RefreshScheduler.refreshNow(ctx)
            return Result.success()
        }

        val settings = ctx.app.settings
        val widgetId = LiveTracker.widgetId(ctx)
        val trajet = (if (widgetId >= 0) settings.trajetFor(widgetId) else null)
            ?: settings.defaultTrajet.first()
        if (trajet == null) {
            LiveTracker.stop(ctx)
            return Result.success()
        }

        val result = runCatching { ctx.app.repository.nextPassages(settings.apiKeyNow(), trajet) }
        LiveTracker.show(
            ctx,
            trajet,
            result.getOrDefault(emptyList()),
            result.exceptionOrNull()?.let { (it as? PrimException)?.message ?: "Erreur réseau" },
        )
        if (LiveTracker.isActive(ctx)) LiveTracker.enqueue(ctx, LiveTracker.REFRESH_SECONDS)
        return Result.success()
    }
}
