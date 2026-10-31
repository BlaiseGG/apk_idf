package com.blaisegg.idfwidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Index des gares et lignes d'Île-de-France embarqué dans l'APK (assets/stations.json,
 * généré à la compilation depuis les données ouvertes IDFM). Recherche, localisation et
 * lignes fonctionnent ainsi sans appel PRIM (ni quota, ni réseau).
 */
class StationIndex private constructor(
    val lines: List<Line>,
    val stations: List<Station>,
    patterns: List<Pattern>,
) {
    /** Parcours distinct d'une ligne : gares (ZdC) dans l'ordre + codes mission RER qui l'empruntent. */
    class Pattern(val line: Line, val missions: List<String>, val trips: Int, val zdcs: IntArray)

    private val patternsByLine = patterns.groupBy { it.line.code }

    /** Parcours de la ligne passant par la station (vide si le GTFS n'est pas embarqué). */
    fun patternsThrough(navitiaStopAreaId: String, lineId: String): List<Pattern> {
        val zdc = navitiaStopAreaId.trimEnd(':').substringAfterLast(':').toIntOrNull() ?: return emptyList()
        return patternsByLine[IdMapper.lineCode(lineId)].orEmpty().filter { p -> p.zdcs.any { it == zdc } }
    }

    fun stationByZdc(zdc: Int): Station? = byZdc[zdc]

    val hasPatterns = patterns.isNotEmpty()
    class Line(val code: String, val shortName: String, val name: String, val category: ModeCategory,
               val modeLabel: String, val color: String, val textColor: String) {
        fun toItem(via: Station? = null, viaMeters: Int? = null) = LineItem(
            id = "line:IDFM:$code", code = shortName, name = name, mode = modeLabel,
            color = color, textColor = textColor,
            viaStopAreaId = via?.navitiaId, viaStopName = via?.name, viaMeters = viaMeters,
        )
    }

    class StationLine(val line: Line, val arrIds: List<Int>, val zdaIds: List<Int>)

    class Station(val zdc: Int, val name: String, val lat: Double, val lon: Double, val lines: List<StationLine>) {
        val navitiaId get() = "stop_area:IDFM:$zdc"
        val norm = IdMapper.normalize(name)
        val modes: Set<ModeCategory> = lines.map { it.line.category }.filter { it != ModeCategory.OTHER }.toSortedSet()
        val rail = lines.any { it.line.category != ModeCategory.BUS }
        fun toStop(distance: Int? = null) = StopItem(navitiaId, name, distance, modes, lat, lon)
        /** Nom sans la commune : « Gare du Nord (Paris) » -> « Gare du Nord ». */
        val shortName: String get() = name.replace(Regex("""\s*\([^)]*\)\s*$"""), "")
    }

    private val byZdc = stations.associateBy { it.zdc }

    fun station(navitiaStopAreaId: String): Station? =
        navitiaStopAreaId.trimEnd(':').substringAfterLast(':').toIntOrNull()?.let { byZdc[it] }

    /** Recherche par nom : tous les mots doivent apparaître ; gares ferrées et débuts de nom d'abord. */
    fun search(query: String, limit: Int = 40): List<StopItem> {
        val words = query.split(' ', '-', '\'').map(IdMapper::normalize).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return stations.asSequence()
            .filter { s -> words.all { s.norm.contains(it) } }
            .sortedWith(compareBy({ if (it.norm.startsWith(words[0])) 0 else 1 }, { if (it.rail) 0 else 1 }, { it.name.length }))
            .take(limit)
            .map { it.toStop() }
            .toList()
    }

    fun nearby(lat: Double, lon: Double, radiusMeters: Int = 1500, limit: Int = 60): List<StopItem> =
        stations.asSequence()
            .map { it to distance(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .take(limit)
            .map { (s, d) -> s.toStop(d) }
            .toList()

    /** Lignes de la station + lignes ferrées des stations de correspondance voisines. */
    fun linesAt(navitiaStopAreaId: String, connectionMeters: Int = 350): List<LineItem>? {
        val st = station(navitiaStopAreaId) ?: return null
        val own = st.lines.map { it.line.toItem() }
        val known = own.map { it.id }.toMutableSet()
        val extra = stations.asSequence()
            .filter { it.zdc != st.zdc && it.rail }
            .map { it to distance(st.lat, st.lon, it.lat, it.lon) }
            .filter { it.second <= connectionMeters }
            .sortedBy { it.second }
            .flatMap { (n, d) ->
                n.lines.asSequence()
                    .filter { it.line.category != ModeCategory.BUS && it.line.category != ModeCategory.OTHER }
                    .map { it.line.toItem(n, d) }
            }
            .filter { known.add(it.id) }
            .toList()
        return (own + extra).sortedWith(compareBy({ it.category.ordinal }, { it.code.padStart(4, '0') }))
    }

    /** Références temps réel de secours pour une ligne à une station (quais et zones d'arrêt). */
    fun fallbackRefs(navitiaStopAreaId: String, lineId: String): List<String> {
        val code = IdMapper.lineCode(lineId)
        val sl = station(navitiaStopAreaId)?.lines?.firstOrNull { it.line.code == code } ?: return emptyList()
        // Métro/tram : quais (ArR). Trains/RER : le temps réel SNCF utilise l'identifiant de zone d'arrêt
        // comme « quai » (ex. Q:43132 à Grigny Centre). On propose les deux formes.
        return (sl.arrIds.map { "STIF:StopPoint:Q:$it:" } +
            sl.zdaIds.map { "STIF:StopPoint:Q:$it:" } +
            sl.zdaIds.map { "STIF:StopArea:SP:$it:" }).distinct()
    }

    companion object {
        private val mutex = Mutex()
        @Volatile private var cached: StationIndex? = null
        @Volatile private var missing = false

        /** null si l'index n'est pas embarqué (compilation locale sans données) : repli sur PRIM. */
        suspend fun get(context: Context): StationIndex? {
            cached?.let { return it }
            if (missing) return null
            return mutex.withLock {
                cached ?: withContext(Dispatchers.IO) {
                    runCatching {
                        context.applicationContext.assets.open("stations.json").bufferedReader().use { parse(it.readText()) }
                    }.getOrNull()
                }.also { cached = it; missing = it == null }
            }
        }

        fun parse(text: String): StationIndex {
            val root = Network.json.parseToJsonElement(text).jsonObject
            val lines = root["lines"]!!.jsonArray.map { e ->
                val a = e.jsonArray
                fun s(i: Int) = a[i].jsonPrimitive.content
                Line(s(0), s(1), s(2), runCatching { ModeCategory.valueOf(s(3)) }.getOrDefault(ModeCategory.OTHER), s(4), s(5), s(6))
            }
            val stations = root["stations"]!!.jsonArray.map { e ->
                val a = e.jsonArray
                Station(
                    zdc = a[0].jsonPrimitive.int,
                    name = a[1].jsonPrimitive.content,
                    lat = a[2].jsonPrimitive.content.toDouble(),
                    lon = a[3].jsonPrimitive.content.toDouble(),
                    lines = a[4].jsonArray.map { le ->
                        val l = le.jsonArray
                        StationLine(
                            lines[l[0].jsonPrimitive.int],
                            (l[1] as JsonArray).map { it.jsonPrimitive.int },
                            (l[2] as JsonArray).map { it.jsonPrimitive.int },
                        )
                    },
                )
            }
            val patterns = root["patterns"]?.jsonArray.orEmpty().mapNotNull { e ->
                val a = e.jsonArray
                val line = lines.getOrNull(a[0].jsonPrimitive.int) ?: return@mapNotNull null
                Pattern(
                    line = line,
                    missions = a[1].jsonArray.map { it.jsonPrimitive.content },
                    trips = a[2].jsonPrimitive.int,
                    zdcs = a[3].jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                )
            }
            return StationIndex(lines, stations, patterns)
        }

        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            val dy = (lat2 - lat1) * 111_195.0
            val dx = (lon2 - lon1) * 111_195.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return sqrt(dx * dx + dy * dy).toInt()
        }
    }
}
