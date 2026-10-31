package com.blaisegg.idfwidget.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.blaisegg.idfwidget.R
import com.blaisegg.idfwidget.data.Passage
import com.blaisegg.idfwidget.data.Trajet
import com.blaisegg.idfwidget.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Suivi en direct : notification permanente « Live Update » (Android 16+) dont la pastille
 * de la barre d'état affiche l'heure du prochain passage (ex. « 10:04 »).
 * Sur les versions plus anciennes : notification permanente classique.
 * Mise à jour ~1 fois par minute, arrêt automatique au bout d'une heure.
 */
object LiveTracker {
    private const val CHANNEL = "live_passages"
    private const val NOTIF_ID = 4242
    private const val WORK = "live-tracking"
    private const val PREFS = "live_tracking"
    private const val KEY_ACTIVE = "active"
    private const val KEY_WIDGET = "widget_id"
    private const val KEY_STARTED = "started_at"
    const val MAX_DURATION_MS = 60 * 60_000L
    const val REFRESH_SECONDS = 60L

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(ctx: Context) = prefs(ctx).getBoolean(KEY_ACTIVE, false)

    /** Widget dont on suit le trajet (-1 = trajet par défaut). */
    fun widgetId(ctx: Context) = prefs(ctx).getInt(KEY_WIDGET, -1)

    fun startedAt(ctx: Context) = prefs(ctx).getLong(KEY_STARTED, 0L)

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context, appWidgetId: Int? = null) {
        prefs(ctx).edit {
            putBoolean(KEY_ACTIVE, true)
            putInt(KEY_WIDGET, appWidgetId ?: -1)
            putLong(KEY_STARTED, System.currentTimeMillis())
        }
        enqueue(ctx, 0)
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit { putBoolean(KEY_ACTIVE, false) }
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    internal fun enqueue(ctx: Context, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<LiveWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun show(ctx: Context, trajet: Trajet, passages: List<Passage>, error: String?) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, build(ctx, trajet, passages, error))
    }

    private fun build(ctx: Context, trajet: Trajet, passages: List<Passage>, error: String?): Notification {
        val now = System.currentTimeMillis()
        val upcoming = passages.filter { it.epochMillis >= now - 30_000 }
        val next = upcoming.firstOrNull()
        val direction = trajet.direction ?: next?.destination.orEmpty()

        val title = when {
            next != null -> "${trajet.lineCode} · ${time(next)} → $direction"
            error != null -> "${trajet.lineCode} → $direction"
            else -> "${trajet.lineCode} · aucun passage prévu"
        }
        val text = buildString {
            append(trajet.stopName)
            if (next != null) append(" · ").append(wait(next, now))
            upcoming.getOrNull(1)?.let { append(", puis ").append(time(it)).append(" (").append(wait(it, now)).append(")") }
            if (error != null) append("\n").append(error)
        }

        val openApp = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, LiveStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(0, "Arrêter le suivi", stop)
            // Android 16 : « Live Update » avec texte court dans la pastille de la barre d'état.
            .setRequestPromotedOngoing(true)
            .apply {
                if (next != null) {
                    setShortCriticalText(time(next))
                    setWhen(next.epochMillis)
                    setShowWhen(true)
                    setUsesChronometer(true)        // compte à rebours en direct dans la notification
                    setChronometerCountDown(true)
                }
            }
            .build()
    }

    private fun time(p: Passage) = hhmm.format(Instant.ofEpochMilli(p.epochMillis))

    private fun wait(p: Passage, now: Long): String {
        val min = ((p.epochMillis - now) / 60_000).coerceAtLeast(0)
        return if (min == 0L) "à quai" else "dans $min min"
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "Suivi en direct", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Heure du prochain passage affichée en haut de l'écran"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}

/** Bouton « Arrêter le suivi » de la notification. */
class LiveStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveTracker.stop(context)
        com.blaisegg.idfwidget.widget.RefreshScheduler.refreshNow(context) // met à jour l'icône du widget
    }
}
