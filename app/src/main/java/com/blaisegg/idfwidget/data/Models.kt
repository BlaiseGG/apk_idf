package com.blaisegg.idfwidget.data

import kotlinx.serialization.Serializable

/** Arrêt (zone d'arrêt Navitia) proposé à l'utilisateur. */
data class StopItem(
    val id: String,          // ex: stop_area:IDFM:71517
    val name: String,
    val distanceMeters: Int? = null,
)

/** Ligne desservant un arrêt. */
data class LineItem(
    val id: String,          // ex: line:IDFM:C01742
    val code: String,        // ex: A, 14, T3a
    val name: String,
    val mode: String,        // ex: RER, Métro, Tramway, Train, Bus
    val color: String,       // hex sans '#'
    val textColor: String,
)

/**
 * Trajet associé à un widget : arrêt + ligne + direction (null = toutes directions).
 * [monitoringRefs] : identifiants SIRI (quais) interrogés sur /stop-monitoring.
 */
@Serializable
data class Trajet(
    val stopAreaId: String,
    val stopName: String,
    val lineId: String,
    val lineCode: String,
    val lineName: String,
    val mode: String,
    val lineColor: String,
    val lineTextColor: String,
    val direction: String? = null,
    val monitoringRefs: List<String> = emptyList(),
    /** Terminus acceptés (ex. « Paris » regroupe Creil, Orry-la-Ville…). Vide = on compare à [direction]. */
    val directionTargets: List<String> = emptyList(),
)

/** Choix proposé à l'étape « Direction ». */
data class DirectionChoice(
    val label: String,            // affiché : « Paris », « Corbeil-Essonnes »…
    val targets: List<String>,    // terminus regroupés derrière ce choix
    val group: DirectionGroup,
)

enum class DirectionGroup { PARIS, OTHER, PARIS_DETAIL }

/** Résultat de l'étape « Direction » pour une ligne à un arrêt. */
data class LineAtStop(val monitoringRefs: List<String>, val choices: List<DirectionChoice>)

/** Un passage temps réel. */
data class Passage(
    val epochMillis: Long,
    val destination: String,
    val atStop: Boolean,
)

class PrimException(message: String, cause: Throwable? = null) : Exception(message, cause)
