package com.blaisegg.idfwidget.data

import com.blaisegg.idfwidget.data.api.CoordDto
import com.blaisegg.idfwidget.data.api.StopPointDto
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
     *  - « Paris » (tous les trains qui se rapprochent de Paris), si l'arrêt est hors de Paris,
     *  - chaque gare desservie après cet arrêt (on choisit simplement là où l'on va),
     *  - en repli, les terminus connus seulement par le temps réel.
     */
    suspend fun lineAtStop(apiKey: String, stopAreaId: String, line: LineItem): LineAtStop = coroutineScope {
        val key = requireKey(apiKey)
        val stopPoints = runCatching { api.stopPoints(key, stopAreaId, line.id).stopPoints }.getOrDefault(emptyList())
        val refs = stopPoints.map { IdMapper.stopPointRef(it.id) }.distinct()
        val stationIds = stopPoints.map { it.id }.toSet()
        val stationDistance = stopPoints.mapNotNull { it.coord.km() }.minOrNull()
        val stationNames = stopPoints.flatMap { listOfNotNull(it.name, it.stopArea?.name) }.map(IdMapper::normalize).toSet()
        val inParis = stationDistance != null && stationDistance < PARIS_RADIUS_KM

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
        val infos = routes.take(MAX_ROUTES).map { route ->
            async { analyzeRoute(key, route.id, terminusOf(route.direction?.stopArea?.name, route.direction?.name, route.name), stationIds, stationDistance) }
        }.awaitAll()

        // Gare desservie -> terminus et missions qui y vont.
        val stations = linkedMapOf<String, StationAcc>()
        val parisTermini = mutableListOf<String>()
        val parisMissions = mutableSetOf<String>()
        for (info in infos) {
            val paris = !inParis && (info.towardsParis ?: IdMapper.normalize(info.terminus).startsWith("paris"))
            if (paris) {
                parisTermini.addUnique(info.terminus)
                parisMissions += info.missions
            }
            info.stationsAfter.forEach { (name, missions) ->
                if (IdMapper.normalize(name) in stationNames) return@forEach
                val acc = stations.getOrPut(IdMapper.normalize(name)) { StationAcc(name) }
                acc.termini.addUnique(info.terminus)
                acc.missions += missions
            }
            // Le terminus lui-même est toujours proposé.
            if (IdMapper.normalize(info.terminus) !in stationNames) {
                stations.getOrPut(IdMapper.normalize(info.terminus)) { StationAcc(info.terminus) }.also {
                    it.termini.addUnique(info.terminus)
                    it.missions += info.missions
                }
            }
        }

        val allMissions = infos.flatMap { it.missions }.toSet()
        val choices = mutableListOf<DirectionChoice>()
        if (parisTermini.isNotEmpty()) {
            choices += DirectionChoice("Paris", parisTermini, DirectionGroup.PARIS, (allMissions - parisMissions).toList())
        }
        stations.values.sortedBy { IdMapper.normalize(it.name) }.forEach {
            choices += DirectionChoice(it.name, it.termini, DirectionGroup.STATION, (allMissions - it.missions).toList())
        }
        // Destinations annoncées en temps réel mais inconnues du référentiel.
        for (label in liveLabels.await()) {
            if (choices.none { c -> c.targets.any { IdMapper.sameDirection(it, label) } }) {
                choices += DirectionChoice(label, listOf(label), DirectionGroup.OTHER)
            }
        }
        LineAtStop(refs, choices)
    }

    private class StationAcc(val name: String) {
        val termini = mutableListOf<String>()
        val missions = mutableSetOf<String>()
    }

    private class RouteInfo(
        val terminus: String,
        val towardsParis: Boolean?,
        val stationsAfter: Map<String, Set<String>>, // gare -> missions qui s'y arrêtent
        val missions: Set<String>,
    )

    private fun terminusOf(vararg names: String?) = names.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun MutableList<String>.addUnique(label: String) {
        if (label.isNotBlank() && none { IdMapper.normalize(it) == IdMapper.normalize(label) }) add(label)
    }

    /** Lit quelques circulations du parcours pour connaître les gares desservies après l'arrêt et le sens. */
    private suspend fun analyzeRoute(
        key: String,
        routeId: String,
        terminus: String,
        stationIds: Set<String>,
        stationDistance: Double?,
    ): RouteInfo {
        val journeys = runCatching { api.routeVehicleJourneys(key, routeId).vehicleJourneys }.getOrDefault(emptyList())
        val stationsAfter = linkedMapOf<String, MutableSet<String>>()
        val missions = mutableSetOf<String>()
        var paris: Boolean? = null
        for (vj in journeys) {
            val idx = vj.stopTimes.indexOfFirst { st -> st.stopPoint?.id?.let { it in stationIds } == true }
            if (idx < 0) continue
            val after = vj.stopTimes.drop(idx + 1).mapNotNull { it.stopPoint }
            if (after.isEmpty()) continue // l'arrêt est le terminus de cette circulation
            val mission = vj.headsign?.trim()?.takeIf { it.isNotEmpty() }
            mission?.let { missions += it }
            after.forEach { sp ->
                val name = sp.stopArea?.name?.takeIf { it.isNotBlank() } ?: sp.name
                if (name.isNotBlank()) stationsAfter.getOrPut(name) { mutableSetOf() }.apply { mission?.let { add(it) } }
            }
            if (paris == null) paris = towardsParis(after, stationDistance)
        }
        return RouteInfo(terminus, paris, stationsAfter, missions)
    }

    /** Se rapproche de Paris après l'arrêt ? (coordonnées, sinon noms de gares contenant « Paris »). */
    private fun towardsParis(after: List<StopPointDto>, stationDistance: Double?): Boolean? {
        val distances = after.mapNotNull { it.coord.km() }
        if (stationDistance != null && distances.isNotEmpty()) return distances.min() < stationDistance - 1.0
        val names = after.map { IdMapper.normalize(it.stopArea?.name ?: it.name) }
        return if (names.any { it.startsWith("paris") }) true else null
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
        val excluded = trajet.excludedMissions.map(IdMapper::normalize).toSet()
        fetchVisits(requireKey(apiKey), trajet.stopAreaId, trajet.monitoringRefs)
            .asSequence()
            .filter { matchesLine(it, trajet.lineId) }
            // Écarte les missions connues pour ne pas s'arrêter à la gare choisie (ex. trains directs).
            .filter { v -> v.journey?.journeyNote?.firstValue()?.let { IdMapper.normalize(it) !in excluded } ?: true }
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
