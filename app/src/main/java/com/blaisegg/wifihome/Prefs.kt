package com.blaisegg.wifihome

import android.content.Context

/** Stores the home location and settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wifi_home", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var latitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lat", 0L))
        set(v) = sp.edit().putLong("lat", java.lang.Double.doubleToRawLongBits(v)).apply()

    var longitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lng", 0L))
        set(v) = sp.edit().putLong("lng", java.lang.Double.doubleToRawLongBits(v)).apply()

    var radius: Float
        get() = sp.getFloat("radius", 150f)
        set(v) = sp.edit().putFloat("radius", v).apply()

    var notifyOnExit: Boolean
        get() = sp.getBoolean("notify_exit", false)
        set(v) = sp.edit().putBoolean("notify_exit", v).apply()
}
