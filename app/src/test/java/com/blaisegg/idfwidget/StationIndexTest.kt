package com.blaisegg.idfwidget

import com.blaisegg.idfwidget.data.ModeCategory
import com.blaisegg.idfwidget.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture de l'index embarqué et recherches locales (sans réseau). */
class StationIndexTest {
    private val json = """
        {"v":1,"lines":[["C01374","4","Métro 4","METRO","Métro","BE418D","FFFFFF"],
          ["C01742","D","RER D","RER","RER","00A88F","FFFFFF"],
          ["C01729","E","RER E","RER","RER","C04191","FFFFFF"],
          ["C00001","38","38","BUS","Bus RATP","5a5a5a","ffffff"]],
         "stations":[
          [71410,"Gare du Nord (Paris)",48.88095,2.35568,[[1,[],[43000]],[0,[22007],[462941]],[3,[999],[5000]]]],
          [73732,"Magenta (Paris)",48.88182,2.35810,[[2,[],[43200]]]],
          [60797,"Grigny Centre (Grigny)",48.6538,2.3957,[[1,[],[43132]]]],
          [6000,"Gare du Nord - Dunkerque (Paris)",48.8810,2.3550,[[3,[998],[5001]]]]]}
    """.trimIndent()

    private val index = StationIndex.parse(json)

    @Test
    fun recherche_par_nom() {
        val r = index.search("gare du nord")
        assertEquals("Gare du Nord (Paris)", r.first().name)   // gare ferrée avant l'arrêt de bus
        assertTrue(index.search("grig").any { it.name.startsWith("Grigny") })
        assertEquals(setOf(ModeCategory.RER, ModeCategory.METRO, ModeCategory.BUS), r.first().modes)
    }

    @Test
    fun arrets_proches() {
        val r = index.nearby(48.8809, 2.3557, 500)
        assertEquals("Gare du Nord (Paris)", r.first().name)
        assertTrue(r.none { it.name.startsWith("Grigny") })
    }

    @Test
    fun correspondance_magenta() {
        val lines = index.linesAt("stop_area:IDFM:71410")!!
        val e = lines.first { it.code == "E" }
        assertEquals("Magenta (Paris)", e.viaStopName)
        assertEquals("stop_area:IDFM:73732", e.viaStopAreaId)
        assertTrue(lines.any { it.code == "D" && it.viaStopName == null })
        assertEquals(1, lines.count { it.code == "38" })        // le bus voisin n'est pas ajouté en double
    }

    @Test
    fun references_temps_reel() {
        assertEquals(
            listOf("STIF:StopPoint:Q:22007:", "STIF:StopArea:SP:462941:"),
            index.fallbackRefs("stop_area:IDFM:71410", "line:IDFM:C01374"),
        )
    }
}
