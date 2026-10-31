package com.blaisegg.idfwidget.data

import kotlinx.serialization.Serializable

/** Arrêt (zone d'arrêt Navitia) proposé à l'utilisateur. */
data class StopItem(
    val id: String,          // ex: stop_area:IDFM:71517
    val name: String,
    val distanceMeters: Int? = null,
    val modes: Set<ModeCategory> = emptySet(), // vide = inconnu
)

/** Familles de transport, dans l'ordre d'affichage. */
enum class ModeCategory(val label: String) {
    RER("RER"), METRO("Métro"), TRAIN("Train"), TRAM("Tram"), BUS("Bus"), OTHER("Autre");

    companion object {
        fun of(mode: String?): ModeCategory {
            val m = IdMapper.normalize(mode)
            return when {
                m.isEmpty() -> OTHER
                m.contains("rer") || m.contains("rapidtransit") -> RER
                m.contains("metro") -> METRO
                m.contains("tram") -> TRAM
                m.contains("train") || m.contains("transilien") || m == "ter" || m.contains("localtrain") -> TRAIN
                m.contains("bus") || m.contains("noctilien") || m.contains("coach") -> BUS
                else -> OTHER
            }
        }
    }
}

/** Ligne desservant un arrêt. */
data class LineItem(
    val id: String,          // ex: line:IDFM:C01742
    val code: String,        // ex: A, 14, T3a
    val name: String,
    val mode: String,        // ex: RER, Métro, Tramway, Train, Bus
    val color: String,       // hex sans '#'
    val textColor: String,
) {
    val category: ModeCategory get() = ModeCategory.of(mode)
}

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
    /** Codes mission RER connus pour NE PAS s'arrêter à la gare choisie (exclus). */
    val excludedMissions: List<String> = emptyList(),
)

/** Choix proposé à l'étape « Direction ». */
data class DirectionChoice(
    val label: String,            // affiché : « Paris », « Grigny Centre »…
    val targets: List<String>,    // terminus des trains concernés
    val group: DirectionGroup,
    val excludedMissions: List<String> = emptyList(),
)

/** PARIS : vers Paris ; STATION : une gare desservie après l'arrêt ; OTHER : terminus seul (repli). */
enum class DirectionGroup { PARIS, STATION, OTHER }

/** Résultat de l'étape « Direction » pour une ligne à un arrêt. */
data class LineAtStop(val monitoringRefs: List<String>, val choices: List<DirectionChoice>)

/** Un passage temps réel. */
data class Passage(
    val epochMillis: Long,
    val destination: String,
    val atStop: Boolean,
)

class PrimException(message: String, cause: Throwable? = null) : Exception(message, cause)
