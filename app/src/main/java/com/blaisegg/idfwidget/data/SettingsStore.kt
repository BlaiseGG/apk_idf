package com.blaisegg.idfwidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Préférences persistantes (DataStore) :
 *  - clé API PRIM,
 *  - trajet par défaut (utilisé par tous les widgets sans configuration propre),
 *  - trajets propres à un widget (clé "widget_<appWidgetId>").
 */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    val apiKey: Flow<String> = store.data.map { it[KEY_API].orEmpty() }
    val defaultTrajet: Flow<Trajet?> = store.data.map { decode(it[KEY_DEFAULT]) }

    suspend fun apiKeyNow(): String = apiKey.first()

    /** Modes masqués dans les listes (ex. Bus). */
    val hiddenModes: Flow<Set<ModeCategory>> = store.data.map { prefs ->
        prefs[KEY_HIDDEN_MODES].orEmpty().mapNotNull { runCatching { ModeCategory.valueOf(it) }.getOrNull() }.toSet()
    }

    suspend fun setHiddenModes(modes: Set<ModeCategory>) {
        store.edit { it[KEY_HIDDEN_MODES] = modes.map { m -> m.name }.toSet() }
    }

    suspend fun setApiKey(key: String) {
        store.edit { it[KEY_API] = key.trim() }
    }

    /** Trajet effectif d'un widget : sa configuration propre, sinon le trajet par défaut. */
    suspend fun trajetFor(appWidgetId: Int): Trajet? {
        val prefs = store.data.first()
        return decode(prefs[widgetKey(appWidgetId)]) ?: decode(prefs[KEY_DEFAULT])
    }

    suspend fun widgetTrajet(appWidgetId: Int): Trajet? = decode(store.data.first()[widgetKey(appWidgetId)])

    /** Depuis l'application : devient le trajet de tous les widgets. */
    suspend fun setDefaultTrajetForAll(trajet: Trajet) {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(WIDGET_PREFIX) }.forEach { prefs.remove(it) }
            prefs[KEY_DEFAULT] = Network.json.encodeToString(Trajet.serializer(), trajet)
        }
    }

    /** Depuis la configuration d'un widget : ne concerne que ce widget. */
    suspend fun setWidgetTrajet(appWidgetId: Int, trajet: Trajet) {
        store.edit { prefs ->
            val encoded = Network.json.encodeToString(Trajet.serializer(), trajet)
            prefs[widgetKey(appWidgetId)] = encoded
            if (prefs[KEY_DEFAULT] == null) prefs[KEY_DEFAULT] = encoded
        }
    }

    suspend fun removeWidget(appWidgetId: Int) {
        store.edit { it.remove(widgetKey(appWidgetId)) }
    }

    private fun decode(raw: String?): Trajet? = raw?.let {
        runCatching { Network.json.decodeFromString(Trajet.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val WIDGET_PREFIX = "widget_"
        val KEY_API = stringPreferencesKey("api_key")
        val KEY_DEFAULT = stringPreferencesKey("default_trajet")
        val KEY_HIDDEN_MODES = stringSetPreferencesKey("hidden_modes")
        fun widgetKey(id: Int) = stringPreferencesKey("$WIDGET_PREFIX$id")
    }
}
