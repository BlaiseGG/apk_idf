package com.blaisegg.wifihome

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.util.Log

object Notifier {
    // New channel id: channel settings can't be changed once created.
    private const val CHANNEL_ID = "home_wifi_alert"
    private const val ID_ARRIVED = 1
    private const val ID_LEFT = 2

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("home_wifi")
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Arrivée / départ maison",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Rappel pour passer de la 5G au Wi-Fi"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** Wi-Fi quick panel (Android 10+) or the Wi-Fi settings page. */
    fun wifiIntent(): Intent =
        Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
            else Settings.ACTION_WIFI_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun internetIntent(): Intent =
        Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else Settings.ACTION_WIRELESS_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activityPi(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alertIntent(context: Context): Intent =
        Intent(context, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Arrival: opens the full-screen alert directly when "display over other apps" is
     * allowed, and posts a sticky notification with a full-screen intent (turns the
     * screen on when the phone is locked).
     */
    fun notifyArrived(context: Context) {
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(alertIntent(context))
            } catch (e: Exception) {
                Log.w("WifiMaison", "Could not open alert", e)
            }
        }
        val alert = activityPi(context, 13, alertIntent(context))
        show(
            context, ID_ARRIVED,
            "🏠 Tu es arrivé chez toi",
            "Le Wi-Fi est éteint : active-le et coupe la 5G.",
            contentIntent = activityPi(context, 11, internetIntent()),
            actionTitle = "Activer le Wi-Fi",
            actionIntent = activityPi(context, 10, wifiIntent()),
            fullScreen = alert,
            ongoing = true
        )
    }

    fun cancelArrived(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVED)
    }

    fun notifyLeft(context: Context) {
        val internet = activityPi(context, 12, internetIntent())
        show(
            context, ID_LEFT,
            "🚶 Tu quittes la maison",
            "Pense à réactiver les données mobiles (5G).",
            contentIntent = internet,
            actionTitle = "Ouvrir les réglages",
            actionIntent = internet,
            fullScreen = null,
            ongoing = false
        )
    }

    private fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent,
        actionTitle: String,
        actionIntent: PendingIntent,
        fullScreen: PendingIntent?,
        ongoing: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_wifi), actionTitle, actionIntent
                ).build()
            )
        if (fullScreen != null) builder.setFullScreenIntent(fullScreen, true)
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }
}
