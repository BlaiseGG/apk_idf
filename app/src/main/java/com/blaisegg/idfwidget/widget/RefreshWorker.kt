package com.blaisegg.idfwidget.widget

import android.content.Context
import android.os.PowerManager
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blaisegg.idfwidget.app
import com.blaisegg.idfwidget.data.PrimException

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val manager = GlanceAppWidgetManager(ctx)
        val allIds = manager.getGlanceIds(PassagesWidget::class.java)
        if (allIds.isEmpty()) return Result.success()

        val periodic = inputData.getBoolean(KEY_PERIODIC, false)
        val followUps = inputData.getInt(KEY_FOLLOW_UPS, 0)
        val interactive = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        // Personne ne regarde l'écran : inutile de consommer réseau et batterie.
        if (periodic && !interactive) return Result.success()

        val targetId = inputData.getInt(KEY_WIDGET_ID, -1)
        val targets = if (targetId == -1) allIds else allIds.filter { manager.getAppWidgetId(it) == targetId }

        for (glanceId in targets) {
            refreshWidget(ctx, glanceId, manager.getAppWidgetId(glanceId))
        }

        if (targetId != -1 && followUps > 0 && interactive) {
            RefreshScheduler.scheduleFollowUp(ctx, targetId, followUps - 1)
        }
        return Result.success()
    }

    companion object {
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_FOLLOW_UPS = "follow_ups"
        const val KEY_PERIODIC = "periodic"

        suspend fun refreshWidget(ctx: Context, glanceId: GlanceId, appWidgetId: Int) {
            val widget = PassagesWidget()
            val trajet = ctx.app.settings.trajetFor(appWidgetId)
            if (trajet == null) {
                updateAppWidgetState(ctx, glanceId) { it[WidgetKeys.status] = Status.NOT_CONFIGURED.name }
                widget.update(ctx, glanceId)
                return
            }

            updateAppWidgetState(ctx, glanceId) {
                it.writeTrajet(trajet)
                it[WidgetKeys.status] = Status.LOADING.name
            }
            widget.update(ctx, glanceId)

            val result = runCatching {
                ctx.app.repository.nextPassages(ctx.app.settings.apiKeyNow(), trajet)
            }
            updateAppWidgetState(ctx, glanceId) { prefs ->
                result.onSuccess { passages ->
                    prefs[WidgetKeys.departures] = passages.take(4).joinToString(",") { it.epochMillis.toString() }
                    prefs[WidgetKeys.status] = if (passages.isEmpty()) Status.EMPTY.name else Status.OK.name
                    prefs[WidgetKeys.updatedAt] = System.currentTimeMillis()
                    prefs[WidgetKeys.theoretical] = passages.isNotEmpty() && passages.all { it.theoretical }
                    prefs.remove(WidgetKeys.message)
                }.onFailure { e ->
                    prefs[WidgetKeys.status] = Status.ERROR.name
                    prefs[WidgetKeys.message] = (e as? PrimException)?.message ?: "Erreur : ${e.message}"
                }
            }
            widget.update(ctx, glanceId)
        }
    }
}
