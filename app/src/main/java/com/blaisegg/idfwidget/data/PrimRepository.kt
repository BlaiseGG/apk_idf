package com.blaisegg.idfwidget.data

import com.blaisegg.idfwidget.data.api.CoordDto
import com.blaisegg.idfwidget.data.api.LineDto
import com.blaisegg.idfwidget.data.api.MonitoredStopVisitDto
import com.blaisegg.idfwidget.data.api.PlaceDto
import com.blaisegg.idfwidget.data.api.PrimApi
import com.blaisegg.idfwidget.data.api.firstValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

class PrimRepository(private val api: PrimApi = Network.primApi) {

    suspend fun searchStops(apiKey: String, query: String): List<StopItem> = call {
        api.searchPlaces(requireKey(apiKey), query).places.mapNotNull { it.toStop() }
    }

    suspend fun nearbyStops(apiKey: String, lat: Double, lon: Double): List<StopItem> = call {
        api.placesNearby(requireKey(apiKey), lon = lon, lat = lat).placesNearby
            .mapNotNull { it.toStop() }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    suspend fun linesAt(apiKey: String, stopAreaId: String): List<LineItem> = call {
        api.linesOfStopArea(requireKey(apiKey), stopAreaId).lines
            .map { it.toLine() }
            .sortedWith(compareBy({ it.category.ordinal }, { it.code.padStart(4, '0') }))
    }

    /**
     * Quais de la ligne à cet arrêt + directions proposées :
     *  1. « Paris » en premier, qui regroupe tous les terminus atteints en se rapprochant de Paris,
     *  2. puis chaque terminus de l'autre sens,
     *  3. puis le détail des terminus côté Paris.
     */
    suspend fun lineAtStop(apiKey: String, stopAreaId: String, line: LineItem): LineAtStop = coroutineScope {
        val key = requireKey(apiKey)
        val stopPoints = runCatching { api.stopPoints(key, stopAreaId, line.id).stopPoints }.getOrDefault(emptyList())
        val refs = stopPoints.map { IdMapper.stopPointRef(it.id) }.distinct()
        val stationIds = stopPoints.map { it.id }.toSet()
        val stationDistance = stopPoints.mapNotNull { it.coord.km() }.minOrNull()

        val liveLabels = async {
            runCatching {
                fetchVisits(key, stopAreaId, refs)
                    .filter { matchesLine(it, line.id) }
                    .mapNotNull { v ->
                        val j = v.journey ?: return@mapNotNull null
                        j.destinationName.firstValue() ?: j.directionName.firstValue()
                    }
            }.getOrDefault(emptyList())
        }

        val routes = runCatching { api.routes(key, stopAreaId, line.id).routes }.getOrDefault(emptyList())
        // Sens de chaque parcours, calculé en parallèle (limité pour préserver le quota).
        val towardsParis = routes.take(MAX_ROUTES).map { route ->
            async { route to isTowardsParis(key, route.id, stationIds, stationDistance) }
        }.awaitAll()

        val parisTermini = mutableListOf<String>()
        val otherTermini = mutableListOf<String>()
        fun MutableList<String>.addUnique(label: String) {
            if (label.isNotBlank() && none { IdMapper.normalize(it) == IdMapper.normalize(label) }) add(label)
        }
        for ((route, paris) in towardsParis) {
            val terminus = route.direction?.stopArea?.name?.takeIf { it.isNotBlank() }
                ?: route.direction?.name?.takeIf { it.isNotBlank() }
                ?: route.name
            val inParis = stationDistance != null && stationDistance < PARIS_RADIUS_KM
            val isParis = !inParis && (paris ?: IdMapper.normalize(terminus).startsWith("paris"))
            if (isParis) parisTermini.addUnique(terminus) else otherTermini.addUnique(terminus)
        }
        // Terminus annoncés en temps réel absents du référentiel : rattachés si possible, sinon « autres ».
        for (label in liveLabels.await()) {
            val known = (parisTermini + otherTermini).any { IdMapper.sameDirection(it, label) }
            if (!known) otherTermini.addUnique(label)
        }
        otherTermini.removeAll { o -> parisTermini.any { IdMapper.normalize(it) == IdMapper.normalize(o) } }

        val choices = buildList {
            if (parisTermini.isNotEmpty()) add(DirectionChoice("Paris", parisTermini.toList(), DirectionGroup.PARIS))
            otherTermini.sortedBy { IdMapper.normalize(it) }
                .forEach { add(DirectionChoice(it, listOf(it), DirectionGroup.OTHER)) }
            if (parisTermini.size > 1) {
                parisTermini.sortedBy { IdMapper.normalize(it) }
                    .forEach { add(DirectionChoice(it, listOf(it), DirectionGroup.PARIS_DETAIL)) }
            }
        }
        LineAtStop(refs, choices)
    }

    /**
     * true si, après cet arrêt, le train se rapproche nettement de Paris (traverse Paris ou s'en approche).
     * null si on ne peut pas le déterminer (pas de données, ou arrêt déjà dans Paris).
     */
    private suspend fun isTowardsParis(
        key: String,
        routeId: String,
        stationIds: Set<String>,
        stationDistance: Double?,
    ): Boolean? {
        if (stationDistance == null || stationDistance < PARIS_RADIUS_KM) return null
        val journeys = runCatching { api.routeVehicleJourneys(key, routeId).vehicleJourneys }.getOrNull() ?: return null
        for (vj in journeys) {
            val idx = vj.stopTimes.indexOfFirst { st -> st.stopPoint?.id?.let { it in stationIds } == true }
            if (idx < 0) continue
            val after = vj.stopTimes.drop(idx + 1).mapNotNull { it.stopPoint?.coord.km() }
            if (after.isEmpty()) return false // cet arrêt est le terminus
            return after.min() < stationDistance - 1.0
        }
        return null
    }

    /** Distance (km) au centre de Paris. */
    private fun CoordDto?.km(): Double? {
        val lat = this?.lat?.toDoubleOrNull() ?: return null
        val lon = this?.lon?.toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val dy = (lat - PARIS_LAT) * 111.2
        val dx = (lon - PARIS_LON) * 111.2 * cos(Math.toRadians(PARIS_LAT))
        return sqrt(dx * dx + dy * dy)
    }

    /** Prochains passages du trajet, triés, uniquement dans le futur. */
    suspend fun nextPassages(apiKey: String, trajet: Trajet): List<Passage> = call {
        val now = System.currentTimeMillis()
        val targets = trajet.directionTargets.ifEmpty { listOfNotNull(trajet.direction) }
        fetchVisits(requireKey(apiKey), trajet.stopAreaId, trajet.monitoringRefs)
            .asSequence()
            .filter { matchesLine(it, trajet.lineId) }
            .mapNotNull { it.toPassage(targets) }
            .filter { it.epochMillis >= now - 30_000 }
            .distinctBy { it.epochMillis / 1000 to it.destination }
            .sortedBy { it.epochMillis }
            .toList()
    }

    // ------------------------------------------------------------------

    private suspend fun fetchVisits(apiKey: String, stopAreaId: String, refs: List<String>): List<MonitoredStopVisitDto> {
        val targets = refs.ifEmpty { listOf(IdMapper.stopAreaRef(stopAreaId)) }
        val results = coroutineScope {
            targets.map { ref -> async { runCatching { api.stopMonitoring(apiKey, ref) } } }.awaitAll()
        }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) throw results.first().exceptionOrNull()!!
        val visits = ok.flatMap { r -> r.siri?.serviceDelivery?.deliveries.orEmpty().flatMap { it.visits } }
        if (visits.isEmpty() && refs.isNotEmpty()) {
            // Repli sur la zone d'arrêt complète si les quais ne renvoient rien.
            return runCatching {
                api.stopMonitoring(apiKey, IdMapper.stopAreaRef(stopAreaId))
                    .siri?.serviceDelivery?.deliveries.orEmpty().flatMap { it.visits }
            }.getOrDefault(emptyList())
        }
        return visits
    }

    private fun matchesLine(v: MonitoredStopVisitDto, lineId: String): Boolean =
        IdMapper.lineCode(v.journey?.lineRef?.value) == IdMapper.lineCode(lineId)

    /** [targets] vide = toutes directions. */
    private fun MonitoredStopVisitDto.toPassage(targets: List<String>): Passage? {
        val j = journey ?: return null
        val c = j.call ?: return null
        if (c.departureStatus.equals("cancelled", true)) return null
        val destination = c.destinationDisplay.firstValue() ?: j.destinationName.firstValue() ?: ""
        if (targets.isNotEmpty()) {
            val candidates = listOf(j.destinationName.firstValue(), destination, j.directionName.firstValue())
            if (candidates.none { c -> targets.any { IdMapper.sameDirection(c, it) } }) return null
        }
        val time = listOf(c.expectedDeparture, c.expectedArrival, c.aimedDeparture, c.aimedArrival)
            .firstNotNullOfOrNull { s -> s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } }
            ?: return null
        return Passage(time, destination, c.vehicleAtStop == true)
    }

    private fun PlaceDto.toStop(): StopItem? {
        val sa = stopArea ?: return null
        if (embeddedType != null && embeddedType != "stop_area") return null
        return StopItem(
            id = sa.id.ifBlank { id },
            name = name.ifBlank { sa.label ?: sa.name },
            distanceMeters = distance?.toDoubleOrNull()?.toInt(),
            modes = (sa.commercialModes + sa.physicalModes)
                .map { ModeCategory.of(it.name.ifBlank { it.id }) }
                .filter { it != ModeCategory.OTHER }
                .toSortedSet(),
        )
    }

    private fun LineDto.toLine() = LineItem(
        id = id,
        code = code.ifBlank { name },
        name = name,
        mode = commercialMode?.name?.takeIf { it.isNotBlank() } ?: physicalModes.firstOrNull()?.name.orEmpty(),
        color = color?.takeIf { it.length == 6 } ?: "5A5A5A",
        textColor = textColor?.takeIf { it.length == 6 } ?: "FFFFFF",
    )

    private companion object {
        const val MAX_ROUTES = 16
        const val PARIS_LAT = 48.8566
        const val PARIS_LON = 2.3488
        const val PARIS_RADIUS_KM = 5.0
    }

    private fun requireKey(key: String): String =
        key.ifBlank { throw PrimException("Clé API PRIM manquante : renseignez-la dans l'application.") }

    /** Traduit les erreurs techniques en messages lisibles. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PrimException) {
        throw e
    } catch (e: HttpException) {
        throw PrimException(
            when (e.code()) {
                401, 403 -> "Clé API refusée. Vérifiez la clé et l'abonnement aux API sur PRIM."
                404 -> "Arrêt ou ligne introuvable."
                429 -> "Quota d'appels PRIM dépassé, réessayez plus tard."
                in 500..599 -> "Service PRIM indisponible (${e.code()})."
                else -> "Erreur PRIM (${e.code()})."
            },
            e,
        )
    } catch (e: IOException) {
        throw PrimException("Pas de connexion réseau.", e)
    } catch (e: SerializationException) {
        throw PrimException("Réponse PRIM illisible.", e)
    } catch (e: Exception) {
        throw PrimException("Erreur inattendue : ${e.message}", e)
    }
}
