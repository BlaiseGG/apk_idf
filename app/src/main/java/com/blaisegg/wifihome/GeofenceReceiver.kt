package com.blaisegg.wifihome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Called by Play Services when entering or leaving home. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("WifiMaison", "Geofence error ${event.errorCode}")
            return
        }
        val prefs = Prefs(context)
        if (!prefs.enabled) return

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (wifi.isWifiEnabled) return // already fine, Android will use Wi-Fi
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Android 9 and older still allow apps to turn Wi-Fi on.
                    @Suppress("DEPRECATION")
                    if (wifi.setWifiEnabled(true)) return
                }
                Notifier.notifyArrived(context)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                if (prefs.notifyOnExit) Notifier.notifyLeft(context)
            }
        }
    }
}
