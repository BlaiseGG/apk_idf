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

object Notifier {
    private const val CHANNEL_ID = "home_wifi"
    private const val ID_ARRIVED = 1
    private const val ID_LEFT = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Arrivée / départ maison",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Rappel pour passer de la 5G au Wi-Fi" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Opens the quick settings panel (Android 10+) or the full settings page. */
    private fun settingsIntent(panelAction: String, fallbackAction: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(panelAction)
        else Intent(fallbackAction)

    private fun activityPi(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun notifyArrived(context: Context) {
        val wifi = activityPi(
            context, 10,
            settingsIntent(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
        )
        val internet = activityPi(
            context, 11,
            settingsIntent(
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                Settings.ACTION_WIRELESS_SETTINGS
            )
        )
        show(
            context, ID_ARRIVED,
            "🏠 Tu es arrivé chez toi",
            "Le Wi-Fi est éteint : active-le et coupe la 5G.",
            contentIntent = internet,
            actionTitle = "Activer le Wi-Fi",
            actionIntent = wifi
        )
    }

    fun notifyLeft(context: Context) {
        val internet = activityPi(
            context, 12,
            settingsIntent(
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                Settings.ACTION_WIRELESS_SETTINGS
            )
        )
        show(
            context, ID_LEFT,
            "🚶 Tu quittes la maison",
            "Pense à réactiver les données mobiles (5G).",
            contentIntent = internet,
            actionTitle = "Ouvrir les réglages",
            actionIntent = internet
        )
    }

    private fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent,
        actionTitle: String,
        actionIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_wifi), actionTitle, actionIntent
                ).build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
