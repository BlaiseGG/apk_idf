package com.blaisegg.idfwidget.data

import java.text.Normalizer

/**
 * Conversion entre les identifiants Navitia (référentiel) et SIRI (temps réel) d'IDFM.
 *   stop_point:IDFM:22092   -> STIF:StopPoint:Q:22092:
 *   stop_area:IDFM:71517    -> STIF:StopArea:SP:71517:
 *   line:IDFM:C01742        -> STIF:Line::C01742:
 */
object IdMapper {
    private fun lastSegment(id: String) = id.trimEnd(':').substringAfterLast(':')

    fun stopPointRef(navitiaStopPointId: String) = "STIF:StopPoint:Q:${lastSegment(navitiaStopPointId)}:"
    fun stopAreaRef(navitiaStopAreaId: String) = "STIF:StopArea:SP:${lastSegment(navitiaStopAreaId)}:"

    /** Code ligne IDFM (C01742) depuis un id Navitia ou une LineRef SIRI. */
    fun lineCode(id: String?): String = id?.let { lastSegment(it) }.orEmpty()

    /** Normalisation pour comparer des libellés de direction (accents, casse, ponctuation). */
    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase().replace("[^a-z0-9]".toRegex(), "")
    }

    fun sameDirection(a: String?, b: String?): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }
}
