package com.blaisegg.wifihome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Geofences are cleared on reboot and app update, so register again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Prefs(context).enabled) return
        val pending = goAsync()
        val task = GeofenceHelper.register(context)
        if (task == null) {
            pending.finish()
        } else {
            task.addOnCompleteListener { pending.finish() }
        }
    }
}
