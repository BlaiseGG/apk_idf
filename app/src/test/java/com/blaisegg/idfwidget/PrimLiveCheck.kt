package com.blaisegg.idfwidget

import com.blaisegg.idfwidget.data.DirectionGroup
import com.blaisegg.idfwidget.data.IdMapper
import com.blaisegg.idfwidget.data.ModeCategory
import com.blaisegg.idfwidget.data.PrimRepository
import com.blaisegg.idfwidget.data.Trajet
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Contrôle sur les vraies données PRIM (lancé par GitHub Actions avec le secret PRIM_API_KEY).
 * Pour chaque gare : lignes trouvées (y compris correspondances), directions, temps réel.
 * Ignoré si la clé n'est pas fournie.
 */
class PrimLiveCheck {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Paris"))
    private fun log(s: String) = println("CHECK| $s")

    private val stations = listOf(
        "Gare du Nord", "Châtelet", "Châtelet les Halles", "Gare de Lyon", "La Défense",
        "Gare Saint-Lazare", "Haussmann Saint-Lazare", "Montparnasse", "Nation", "Saint-Michel Notre-Dame",
        "Grigny Centre", "Juvisy", "Massy Palaiseau", "Gare de l'Est", "Marne-la-Vallée Chessy",
    )

    /** Pour un trajet sans passage : que renvoie vraiment le temps réel pour chaque quai ? */
    private suspend fun diagnose(key: String, trajet: Trajet) {
        val api = com.blaisegg.idfwidget.data.Network.primApi
        val refs = trajet.monitoringRefs + IdMapper.stopAreaRef(trajet.stopAreaId)
        for (ref in refs) {
            val r = runCatching { api.stopMonitoring(key, ref) }
            val visits = r.getOrNull()?.siri?.serviceDelivery?.deliveries.orEmpty().flatMap { it.visits }
            val lineCodes = visits.mapNotNull { IdMapper.lineCode(it.journey?.lineRef?.value) }.groupingBy { it }.eachCount()
            log("      diag $ref -> ${r.exceptionOrNull()?.message ?: "${visits.size} visites, lignes=$lineCodes"} (attendu ${IdMapper.lineCode(trajet.lineId)})")
        }
    }

    @Test
    fun report() = runBlocking {
        val key = System.getenv("PRIM_API_KEY").orEmpty()
        Assume.assumeTrue("PRIM_API_KEY absente", key.isNotBlank())
        val repo = PrimRepository()

        for (q in stations) {
            val found = runCatching { repo.searchStops(key, q) }
            val stop = found.getOrNull()?.firstOrNull()
            if (stop == null) {
                log("XX $q : introuvable (${found.exceptionOrNull()?.message})")
                continue
            }
            log("== ${stop.name} [${stop.id}] modes=${stop.modes.joinToString { it.label }} coord=${stop.lat},${stop.lon}")
            val linesResult = runCatching { repo.linesAt(key, stop) }
            val lines = linesResult.getOrNull()
            if (lines == null) {
                log("   XX lignes : ${linesResult.exceptionOrNull()?.message}")
                continue
            }
            val bus = lines.count { it.category == ModeCategory.BUS }
            for (line in lines.filter { it.category != ModeCategory.BUS }) {
                val via = line.viaStopName?.let { " (via $it ${line.viaMeters} m)" }.orEmpty()
                val stopAreaId = line.viaStopAreaId ?: stop.id
                val las = runCatching { repo.lineAtStop(key, stopAreaId, line) }
                val info = las.getOrNull()
                val trajet = Trajet(
                    stopAreaId = stopAreaId, stopName = stop.name, lineId = line.id, lineCode = line.code,
                    lineName = line.name, mode = line.mode, lineColor = line.color, lineTextColor = line.textColor,
                    monitoringRefs = info?.monitoringRefs.orEmpty(),
                )
                val live = runCatching { repo.nextPassages(key, trajet) }
                val liveText = live.fold(
                    { p -> "${p.size} passages" + p.take(3).joinToString(prefix = " [", postfix = "]") { hhmm.format(Instant.ofEpochMilli(it.epochMillis)) + " " + it.destination } },
                    { "ERREUR ${it.message}" },
                )
                val dirText = las.fold(
                    { i ->
                        val paris = i.choices.any { it.group == DirectionGroup.PARIS }
                        val missions = i.choices.flatMap { it.missions }.distinct()
                        "quais=${i.monitoringRefs.size} gares=${i.choices.count { it.group == DirectionGroup.STATION }} paris=$paris missions=${missions.size}"
                    },
                    { "ERREUR directions ${it.message}" },
                )
                val flag = if (live.isFailure || las.isFailure || (live.getOrNull()?.isEmpty() == true)) "!!" else "ok"
                log("   $flag ${line.category.label} ${line.code}$via | $dirText | $liveText")
                if (live.getOrNull()?.isEmpty() == true) diagnose(key, trajet)
                delay(300)
            }
            log("   bus: $bus lignes")
        }

        // Cas réel signalé : Grigny Centre, RER D, vers Gare du Nord (les VOPA doivent apparaître).
        val grigny = repo.searchStops(key, "Grigny Centre").firstOrNull() ?: return@runBlocking
        val d = repo.linesAt(key, grigny).firstOrNull { it.code == "D" && it.category == ModeCategory.RER } ?: return@runBlocking
        val info = repo.lineAtStop(key, grigny.id, d)
        val gdn = info.choices.firstOrNull { IdMapper.normalize(it.label).contains("garedunord") }
        log("== Grigny D -> Gare du Nord : choix=${gdn?.label} terminus=${gdn?.targets} missions=${gdn?.missions} exclues=${gdn?.excludedMissions}")
        if (gdn != null) {
            val t = Trajet(
                stopAreaId = grigny.id, stopName = grigny.name, lineId = d.id, lineCode = d.code, lineName = d.name,
                mode = d.mode, lineColor = d.color, lineTextColor = d.textColor, direction = gdn.label,
                monitoringRefs = info.monitoringRefs, directionTargets = gdn.targets,
                directionMissions = gdn.missions, excludedMissions = gdn.excludedMissions,
            )
            repo.nextPassages(key, t).take(6).forEach {
                log("   ${hhmm.format(Instant.ofEpochMilli(it.epochMillis))} -> ${it.destination}")
            }
        }
        log("== Directions proposées à Grigny (D) : " + info.choices.joinToString { "${it.group}:${it.label}" })
    }
}
