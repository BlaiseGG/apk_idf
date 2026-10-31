package com.blaisegg.idfwidget.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blaisegg.idfwidget.app
import com.blaisegg.idfwidget.data.DirectionChoice
import com.blaisegg.idfwidget.data.LineAtStop
import com.blaisegg.idfwidget.data.LineItem
import com.blaisegg.idfwidget.data.ModeCategory
import com.blaisegg.idfwidget.data.Passage
import com.blaisegg.idfwidget.data.PrimException
import com.blaisegg.idfwidget.data.StopItem
import com.blaisegg.idfwidget.data.Trajet
import com.blaisegg.idfwidget.live.LiveTracker
import com.blaisegg.idfwidget.location.LocationHelper
import com.blaisegg.idfwidget.widget.RefreshScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Step { LOADING, API_KEY, SEARCH, LINES, DIRECTIONS, SUMMARY }

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

data class UiState(
    val step: Step = Step.LOADING,
    val apiKey: String = "",
    val query: String = "",
    val stops: Load<List<StopItem>> = Load.Idle,
    val stopsNearby: Boolean = false,
    val stop: StopItem? = null,
    val lines: Load<List<LineItem>> = Load.Idle,
    val line: LineItem? = null,
    val directions: Load<LineAtStop> = Load.Idle,
    val trajet: Trajet? = null,        // trajet affiché dans le récapitulatif
    val saved: Boolean = false,        // déjà associé au(x) widget(s) ?
    val preview: Load<List<Passage>> = Load.Idle,
    val finished: Boolean = false,     // mode configuration : fermer l'écran
    val snackbar: String? = null,
    val hiddenModes: Set<ModeCategory> = emptySet(),
    val liveActive: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.app.settings
    private val repo = application.app.repository
    private val location = LocationHelper(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** null = application ; sinon identifiant du widget en cours de configuration. */
    private var widgetId: Int? = null
    private var searchJob: Job? = null
    private var started = false

    fun start(appWidgetId: Int?) {
        if (started) return
        started = true
        widgetId = appWidgetId
        viewModelScope.launch {
            val key = settings.apiKey.first()
            val hidden = settings.hiddenModes.first()
            val widgetTrajet = appWidgetId?.let { settings.widgetTrajet(it) }
            val default = settings.defaultTrajet.first()
            val trajet = widgetTrajet ?: default
            // En mode widget, le trajet par défaut est proposé mais doit être validé.
            val saved = if (appWidgetId == null) trajet != null else widgetTrajet != null
            _state.update {
                it.copy(
                    apiKey = key,
                    hiddenModes = hidden,
                    trajet = trajet,
                    saved = saved,
                    step = when {
                        key.isBlank() -> Step.API_KEY
                        trajet != null -> Step.SUMMARY
                        else -> Step.SEARCH
                    },
                )
            }
            if (key.isNotBlank() && trajet != null) loadPreview()
        }
    }

    // ---------------- Clé API ----------------

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            settings.setApiKey(key)
            val k = key.trim()
            _state.update {
                it.copy(
                    apiKey = k,
                    step = if (it.trajet != null) Step.SUMMARY else Step.SEARCH,
                    snackbar = "Clé API enregistrée",
                )
            }
            if (_state.value.trajet != null) loadPreview()
            RefreshScheduler.refreshNow(getApplication())
        }
    }

    fun editApiKey() = _state.update { it.copy(step = Step.API_KEY) }

    // ---------------- Recherche d'arrêt ----------------

    fun startSearch() = _state.update { it.copy(step = Step.SEARCH) }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            if (!_state.value.stopsNearby) _state.update { it.copy(stops = Load.Idle) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // anti-rebond pendant la frappe
            _state.update { it.copy(stops = Load.Loading, stopsNearby = false) }
            val key = _state.value.apiKey
            val result = load { repo.searchStops(key, q.trim()) }
            _state.update { it.copy(stops = result) }
        }
    }

    fun locate() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!location.isEnabled()) {
                _state.update {
                    it.copy(stops = Load.Err("Localisation désactivée. Activez-la ou recherchez une station par son nom."))
                }
                return@launch
            }
            _state.update { it.copy(stops = Load.Loading, stopsNearby = true, query = "") }
            val loc = location.currentLocation()
            if (loc == null) {
                _state.update { it.copy(stops = Load.Err("Position introuvable. Réessayez ou recherchez manuellement.")) }
                return@launch
            }
            val key = _state.value.apiKey
            val result = load { repo.nearbyStops(key, loc.latitude, loc.longitude) }
                .let { r -> if (r is Load.Ok && r.data.isEmpty()) Load.Err("Aucun arrêt à moins d'1 km.") else r }
            _state.update { it.copy(stops = result) }
        }
    }

    fun onLocationDenied() = _state.update {
        it.copy(stops = Load.Err("Permission de localisation refusée : recherchez la station par son nom."))
    }

    fun refreshLiveState() = _state.update { it.copy(liveActive = LiveTracker.isActive(getApplication())) }

    fun toggleMode(mode: ModeCategory) {
        val hidden = _state.value.hiddenModes.let { if (mode in it) it - mode else it + mode }
        _state.update { it.copy(hiddenModes = hidden) }
        viewModelScope.launch { settings.setHiddenModes(hidden) }
    }

    // ---------------- Ligne ----------------

    fun selectStop(stop: StopItem) {
        _state.update { it.copy(stop = stop, step = Step.LINES, lines = Load.Loading) }
        viewModelScope.launch {
            val key = _state.value.apiKey
            val result = load { repo.linesAt(key, stop.id) }
                .let { r -> if (r is Load.Ok && r.data.isEmpty()) Load.Err("Aucune ligne trouvée pour cet arrêt.") else r }
            _state.update { it.copy(lines = result) }
        }
    }

    // ---------------- Direction ----------------

    fun selectLine(line: LineItem) {
        val stop = _state.value.stop ?: return
        _state.update { it.copy(line = line, step = Step.DIRECTIONS, directions = Load.Loading) }
        viewModelScope.launch {
            val key = _state.value.apiKey
            val result = load { repo.lineAtStop(key, stop.id, line) }
            _state.update { it.copy(directions = result) }
        }
    }

    /** [choice] null = toutes directions. Affiche le récapitulatif à valider. */
    fun selectDirection(choice: DirectionChoice?) {
        val s = _state.value
        val stop = s.stop ?: return
        val line = s.line ?: return
        val refs = (s.directions as? Load.Ok<LineAtStop>)?.data?.monitoringRefs.orEmpty()
        val trajet = Trajet(
            stopAreaId = stop.id,
            stopName = stop.name,
            lineId = line.id,
            lineCode = line.code,
            lineName = line.name,
            mode = line.mode,
            lineColor = line.color,
            lineTextColor = line.textColor,
            direction = choice?.label,
            monitoringRefs = refs,
            directionTargets = choice?.targets.orEmpty(),
        )
        _state.update { it.copy(trajet = trajet, saved = false, step = Step.SUMMARY) }
        loadPreview()
    }

    // ---------------- Récapitulatif & validation ----------------

    fun loadPreview() {
        val s = _state.value
        val trajet = s.trajet ?: return
        _state.update { it.copy(preview = Load.Loading) }
        viewModelScope.launch {
            val result = load { repo.nextPassages(s.apiKey, trajet) }
            _state.update { it.copy(preview = result) }
        }
    }

    fun validate() {
        val trajet = _state.value.trajet ?: return
        viewModelScope.launch {
            val id = widgetId
            if (id == null) {
                settings.setDefaultTrajetForAll(trajet)
                RefreshScheduler.refreshNow(getApplication())
            } else {
                settings.setWidgetTrajet(id, trajet)
                RefreshScheduler.refreshNow(getApplication(), id)
            }
            RefreshScheduler.ensurePeriodic(getApplication())
            _state.update {
                it.copy(
                    saved = true,
                    finished = id != null,
                    snackbar = if (id == null) "Trajet associé au(x) widget(s)" else null,
                )
            }
        }
    }

    fun back(): Boolean {
        val s = _state.value
        val previous = when (s.step) {
            Step.LINES -> Step.SEARCH
            Step.DIRECTIONS -> Step.LINES
            Step.SEARCH -> if (s.trajet != null) Step.SUMMARY else null
            Step.API_KEY -> if (s.apiKey.isNotBlank()) (if (s.trajet != null) Step.SUMMARY else Step.SEARCH) else null
            else -> null
        } ?: return false
        _state.update { it.copy(step = previous) }
        return true
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ok(block())
    } catch (e: PrimException) {
        Load.Err(e.message ?: "Erreur")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Err(e.message ?: "Erreur inattendue")
    }
}
