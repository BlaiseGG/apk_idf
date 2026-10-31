package com.blaisegg.idfwidget.data

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
            .sortedWith(compareBy({ modeOrder(it.mode) }, { it.code.padStart(4, '0') }))
    }

    /** Quais (SIRI StopPoint) de la ligne dans cette zone d'arrêt. */
    suspend fun monitoringRefs(apiKey: String, stopAreaId: String, lineId: String): List<String> = call {
        api.stopPoints(requireKey(apiKey), stopAreaId, lineId).stopPoints
            .map { IdMapper.stopPointRef(it.id) }
            .distinct()
    }

    /**
     * Directions possibles : celles annoncées en temps réel (libellés identiques à ceux
     * qu'on filtrera ensuite) complétées par les terminus théoriques Navitia.
     */
    suspend fun directions(apiKey: String, stopAreaId: String, line: LineItem, refs: List<String>): List<String> =
        coroutineScope {
            val key = requireKey(apiKey)
            val theoretical = async {
                runCatching {
                    api.routes(key, stopAreaId, line.id).routes.mapNotNull { r ->
                        r.direction?.stopArea?.name?.takeIf { it.isNotBlank() }
                            ?: r.direction?.name?.takeIf { it.isNotBlank() }
                            ?: r.name.takeIf { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
            }
            val live = async {
                runCatching {
                    fetchVisits(key, stopAreaId, refs)
                        .filter { matchesLine(it, line.id) }
                        .mapNotNull { v ->
                            val j = v.journey ?: return@mapNotNull null
                            j.directionName.firstValue() ?: j.destinationName.firstValue()
                        }
                }.getOrDefault(emptyList())
            }
            val all = live.await() + theoretical.await()
            val result = mutableListOf<String>()
            for (label in all) {
                if (result.none { IdMapper.normalize(it) == IdMapper.normalize(label) }) result += label
            }
            result
        }

    /** Prochains passages du trajet, triés, uniquement dans le futur. */
    suspend fun nextPassages(apiKey: String, trajet: Trajet): List<Passage> = call {
        val now = System.currentTimeMillis()
        fetchVisits(requireKey(apiKey), trajet.stopAreaId, trajet.monitoringRefs)
            .asSequence()
            .filter { matchesLine(it, trajet.lineId) }
            .mapNotNull { it.toPassage(trajet.direction) }
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

    private fun MonitoredStopVisitDto.toPassage(direction: String?): Passage? {
        val j = journey ?: return null
        val c = j.call ?: return null
        if (c.departureStatus.equals("cancelled", true)) return null
        val destination = c.destinationDisplay.firstValue() ?: j.destinationName.firstValue() ?: ""
        if (direction != null) {
            val candidates = listOf(j.directionName.firstValue(), j.destinationName.firstValue(), destination)
            if (candidates.none { IdMapper.sameDirection(it, direction) }) return null
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

    private fun modeOrder(mode: String): Int {
        val m = IdMapper.normalize(mode)
        return when {
            m.contains("rer") -> 0
            m.contains("metro") -> 1
            m.contains("train") || m.contains("transilien") || m.contains("ter") -> 2
            m.contains("tram") -> 3
            m.contains("bus") -> 5
            else -> 4
        }
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
