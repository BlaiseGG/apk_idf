package com.blaisegg.idfwidget.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position courante via LocationManager (pas de dépendance aux Google Play Services,
 * fonctionne aussi sur les téléphones sans GMS). L'appelant doit avoir obtenu la permission.
 */
class LocationHelper(context: Context) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(lm)

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val loc = withTimeoutOrNull(10_000) { requestSingle(provider) }
            if (loc != null) return loc
        }
        // Repli : dernière position connue la plus récente.
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                lm, provider, signal, ContextCompat.getMainExecutor(appContext),
            ) { location -> if (cont.isActive) cont.resume(location) }
        }
}
