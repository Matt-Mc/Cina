package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class EssentialsTest {
    private val centre=GeoPoint(45.0,-75.0)
    private fun response(elements: String,extra: String="") = """{"osm3s":{"timestamp_osm_base":"2026-10-31T12:00:00Z"},"elements":[$elements]$extra}"""
    @Test fun distanceHandlesDateLineAndPoles() {
        assertEquals(0.0,centre.distanceTo(centre),0.001)
        assertEquals(22239.0,GeoPoint(0.0,179.9).distanceTo(GeoPoint(0.0,-179.9)),5.0)
        assertTrue(GeoPoint(90.0,0.0).distanceTo(GeoPoint(90.0,180.0))<0.01)
    }
    @Test fun indexedQueriesCoverTheCircleIncludingDateLineAndPoles() {
        fun bounds(point: GeoPoint) = Regex("\\[bbox:([^]]+)\\]").find(Essentials.query(point, 20))!!.groupValues[1].split(',').map(String::toDouble)
        val ordinary = bounds(centre)
        assertTrue(ordinary[0] < centre.lat && ordinary[2] > centre.lat)
        assertTrue(ordinary[1] < centre.lon && ordinary[3] > centre.lon)
        val crossing = bounds(GeoPoint(0.0, 179.99))
        assertTrue(crossing[1] > crossing[3])
        val pole = bounds(GeoPoint(90.0, 0.0))
        assertEquals(-180.0, pole[1], 0.0); assertEquals(180.0, pole[3], 0.0)
        assertTrue(Essentials.query(centre, 5).endsWith("out body center;"))
        assertFalse(Essentials.query(centre, 5).contains("around:"))
    }
    @Test fun invalidCoordinatesAndRadiusAreRejected() {
        listOf(Double.NaN,Double.POSITIVE_INFINITY,91.0).forEach { lat -> assertThrows(IllegalArgumentException::class.java) { GeoPoint(lat,0.0) } }
        assertThrows(IllegalArgumentException::class.java) { Essentials.query(centre,21) }
    }
    @Test fun partialServiceResponsesNeverBecomeInstalledSnapshots() {
        val error = assertThrows(IncompleteMapException::class.java) { Essentials.parse(response("",",\"remark\":\"runtime error: timed out\""),centre,5) }
        assertTrue(EssentialsDownload.retryable(error))
    }
    @Test fun parsingKeepsSourcesDeduplicatesAndDoesNotInventDetails() {
        val node="""{"type":"node","id":1,"lat":45.001,"lon":-75,"tags":{"amenity":"pharmacy","name":"Local pharmacy"}}"""
        val parsed=Essentials.parse(response("$node,$node"),centre,5)
        assertEquals(1,parsed.places.size)
        val place=parsed.places.single()
        assertEquals("https://www.openstreetmap.org/node/1",place.source)
        assertTrue(Essentials.details(place).contains("Hours in snapshot: not recorded"))
        assertFalse(Essentials.details(place).contains("Emergency department:"))
    }
    @Test fun wayCentresOutsideCoverageAndUnknownCategoriesAreExcluded() {
        val elements="""{"type":"way","id":2,"center":{"lat":46,"lon":-75},"tags":{"amenity":"hospital"}},
            {"type":"node","id":3,"lat":45,"lon":-75,"tags":{"amenity":"restaurant"}},
            {"type":"way","id":4,"center":{"lat":45.001,"lon":-75},"tags":{"amenity":"toilets","wheelchair":"limited","fee":"yes"}}"""
        val places=Essentials.parse(response(elements),centre,5).places
        assertEquals(listOf("way/4"),places.map { it.id })
        assertTrue(Essentials.details(places.single()).contains("Wheelchair access: limited"))
    }
    @Test fun emptyValidAreaIsDifferentFromFailedDownload() {
        assertTrue(Essentials.parse(response(""),centre,5).places.isEmpty())
    }
    @Test fun nearbyLookupsRouteLocallyWhileCurrentRequestsRemainOnline() {
        assertTrue(Essentials.lookup("Where is the nearest pharmacy?"))
        assertTrue(Essentials.lookup("Find an ATM near me"))
        assertFalse(Essentials.lookup("Explain what a pharmacy does"))
        assertFalse(Essentials.lookup("Find information about hospital funding"))
        assertFalse(Essentials.lookup("Where does hospital funding come from?"))
        assertTrue(Essentials.explicitlyOnline("Which pharmacy is open now?"))
        assertTrue(ToolCatalog.select("Where is the nearest pharmacy?",web=false).any { it.name=="search_essential_places" })
        assertFalse(ToolCatalog.select("Where is the nearest pharmacy?",scheduled=true).any { it.name=="search_essential_places" })
    }
}
