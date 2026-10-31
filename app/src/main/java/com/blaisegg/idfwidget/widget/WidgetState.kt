package com.blaisegg.idfwidget.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.blaisegg.idfwidget.data.Trajet

/** Clés de l'état Glance de chaque widget (ce qui est affiché). */
object WidgetKeys {
    val status = stringPreferencesKey("status")            // Status.name
    val departures = stringPreferencesKey("departures")    // epoch millis séparés par ','
    val message = stringPreferencesKey("message")
    val updatedAt = longPreferencesKey("updated_at")
    val stopName = stringPreferencesKey("stop_name")
    val lineCode = stringPreferencesKey("line_code")
    val lineColor = stringPreferencesKey("line_color")
    val lineTextColor = stringPreferencesKey("line_text_color")
    val direction = stringPreferencesKey("direction")
    val mode = stringPreferencesKey("mode")
}

enum class Status { NOT_CONFIGURED, LOADING, OK, EMPTY, ERROR }

fun MutablePreferences.writeTrajet(t: Trajet) {
    this[WidgetKeys.stopName] = t.stopName
    this[WidgetKeys.lineCode] = t.lineCode
    this[WidgetKeys.lineColor] = t.lineColor
    this[WidgetKeys.lineTextColor] = t.lineTextColor
    this[WidgetKeys.direction] = t.direction ?: "Toutes directions"
    this[WidgetKeys.mode] = t.mode
}

fun Preferences.status(): Status =
    this[WidgetKeys.status]?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: Status.NOT_CONFIGURED

fun Preferences.departures(): List<Long> =
    this[WidgetKeys.departures].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
