package com.blaisegg.wifihome

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task

/** Registers / removes the "home" geofence with Google Play Services. */
object GeofenceHelper {
    private const val HOME_ID = "home"

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        // Must be mutable: Play Services adds the geofence event to the intent.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun hasLocationPermissions(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    /** Returns null if permissions are missing. */
    @SuppressLint("MissingPermission")
    fun register(context: Context): Task<Void>? {
        if (!hasLocationPermissions(context)) return null
        val prefs = Prefs(context)
        val geofence = Geofence.Builder()
            .setRequestId(HOME_ID)
            .setCircularRegion(prefs.latitude, prefs.longitude, prefs.radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // no alert right after saving the home position
            .addGeofence(geofence)
            .build()
        return LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
    }

    fun unregister(context: Context): Task<Void> =
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
}
