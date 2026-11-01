package dev.pocketmuse

import org.json.JSONObject
import kotlin.math.*

/** Collection kinds are independent of their delivery and search implementations. */
internal data class CollectionType(val id: String, val title: String, val description: String)
internal object CollectionCatalog {
    val essentials = CollectionType("osm-essentials", "Essentials", "Pharmacies, hospitals, ATMs, toilets, drinking water, stations and everyday places from OpenStreetMap.")
    val available = listOf(essentials)
}

internal data class GeoPoint(val lat: Double, val lon: Double) {
    init { require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) { "Enter valid latitude and longitude." } }
    fun distanceTo(other: GeoPoint): Double {
        val a = sin(Math.toRadians(other.lat-lat)/2).pow(2) + cos(Math.toRadians(lat))*cos(Math.toRadians(other.lat))*sin(Math.toRadians(other.lon-lon)/2).pow(2)
        return 6371008.8 * 2 * asin(sqrt(a.coerceIn(0.0,1.0)))
    }
}
internal data class EssentialKind(val id: String, val title: String, val key: String, val value: String, val words: String)
internal data class EssentialPlace(val id: String, val kind: String, val name: String, val point: GeoPoint, val details: String) {
    val source: String get() = "https://www.openstreetmap.org/$id"
}
internal data class EssentialsSnapshot(val timestamp: String, val places: List<EssentialPlace>)

internal object Essentials {
    val kinds = listOf(
        EssentialKind("pharmacy","Pharmacies","amenity","pharmacy","pharmacy|pharmacies|drugstore|drugstores"),
        EssentialKind("hospital","Hospitals","amenity","hospital","hospital|hospitals|emergency department"),
        EssentialKind("clinic","Clinics","amenity","clinic","clinic|clinics"),
        EssentialKind("atm","ATMs","amenity","atm","atm|atms|cash machine"),
        EssentialKind("toilets","Toilets","amenity","toilets","toilet|toilets|restroom|restrooms|bathroom|bathrooms"),
        EssentialKind("water","Drinking water","amenity","drinking_water","drinking water|water fountain|water fountains"),
        EssentialKind("train","Train stations","railway","station","train station|railway station|train stations|railway stations"),
        EssentialKind("bus","Bus stations","amenity","bus_station","bus station|bus stations"),
        EssentialKind("ferry","Ferry terminals","amenity","ferry_terminal","ferry|ferry terminal|ferry terminals"),
        EssentialKind("police","Police","amenity","police","police"),
        EssentialKind("embassy","Embassies","amenity","embassy","embassy|embassies"),
        EssentialKind("exchange","Currency exchange","amenity","bureau_de_change","currency exchange|bureau de change"),
        EssentialKind("supermarket","Supermarkets","shop","supermarket","supermarket|supermarkets|grocery store|grocery stores"),
        EssentialKind("hostel","Hostels","tourism","hostel","hostel|hostels")
    )
    fun categories(query: String): List<String> = kinds.filter { Regex("\\b(?:${it.words})\\b",RegexOption.IGNORE_CASE).containsMatchIn(query) }.map { it.id }
    fun lookup(query: String): Boolean {
        if(categories(query).isEmpty() || TurnDecision.isWritingRequest(query)) return false
        if(Regex("\\b(information|research|history|explain|meaning|definition|funding)\\b",RegexOption.IGNORE_CASE).containsMatchIn(query)) return false
        return Regex("\\b(nearest|nearby|near|closest|locate)\\b|\\bwhere (?:is|are|can I find)\\b|\\bfind (?:an? |the )?(?:${kinds.joinToString("|") { it.words }})\\b",RegexOption.IGNORE_CASE).containsMatchIn(query)
    }
    fun explicitlyOnline(query: String) = Regex("\\b(online|web|internet|live|current|open now|right now|today)\\b",RegexOption.IGNORE_CASE).containsMatchIn(query)
    fun query(point: GeoPoint, radiusKm: Int): String {
        require(radiusKm in 1..20)
        val selectors = kinds.groupBy { it.key }.map { (key, group) ->
            "[\"$key\"~\"^(${group.joinToString("|") { it.value }})$\"]"
        } + "[\"office\"=\"diplomatic\"][\"diplomatic\"~\"^(embassy|consulate|high_commission)$\"]"
        // Let Overpass use its bounding-box index, then enforce the circle locally in parse().
        // This avoids repeating expensive around geometry checks for every category group.
        val angle = radiusKm * 1000 / 6371008.8
        val deltaLat = Math.toDegrees(angle)
        val south = (point.lat - deltaLat).coerceAtLeast(-90.0)
        val north = (point.lat + deltaLat).coerceAtMost(90.0)
        val polar = south == -90.0 || north == 90.0
        val deltaLon = if (polar) 180.0 else Math.toDegrees(asin((sin(angle) / cos(Math.toRadians(point.lat))).coerceIn(-1.0, 1.0)))
        fun wrap(lon: Double) = (lon + 540.0) % 360.0 - 180.0
        val west = if (polar) -180.0 else wrap(point.lon - deltaLon)
        val east = if (polar) 180.0 else wrap(point.lon + deltaLon)
        // 'out tags' omits node coordinates. Body retains them; center adds way/relation locations.
        return "[out:json][timeout:60][maxsize:33554432][bbox:$south,$west,$north,$east];(" + selectors.joinToString("") { "nwr$it;" } + ");out body center;"
    }
    fun parse(raw: String, centre: GeoPoint, radiusKm: Int): EssentialsSnapshot {
        val root = JSONObject(raw)
        if (root.optString("remark").isNotBlank()) {
            throw IncompleteMapException()
        }
        val timestamp = root.getJSONObject("osm3s").getString("timestamp_osm_base")
        java.time.Instant.parse(timestamp)
        val elements = root.getJSONArray("elements")
        require(elements.length() <= 40000) { "This area contains too many places. Choose a smaller radius." }
        val places = linkedMapOf<String,EssentialPlace>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val type = e.getString("type")
            require(type in setOf("node","way","relation") && e.getLong("id") > 0) { "Invalid map record." }
            val tags = e.optJSONObject("tags") ?: continue
            val kind = kinds.firstOrNull { tags.optString(it.key) == it.value }?.id
                ?: if(tags.optString("office")=="diplomatic" && tags.optString("diplomatic") in setOf("embassy","consulate","high_commission")) "embassy" else continue
            val coordinates = if(type=="node") e else e.optJSONObject("center") ?: continue
            val point = GeoPoint(coordinates.getDouble("lat"),coordinates.getDouble("lon"))
            // Ways can intersect the requested area while their representative centre lies outside it.
            if(centre.distanceTo(point)>radiusKm*1000) continue
            val detail = JSONObject()
            listOf("addr:housenumber","addr:street","addr:city","addr:postcode","opening_hours","wheelchair","emergency","fee","access","phone","contact:phone","check_date","survey:date","diplomatic").forEach { key ->
                tags.optString(key).takeIf { it.isNotBlank() }?.let { detail.put(key,it.take(500)) }
            }
            val name = listOf("name","brand","operator").firstNotNullOfOrNull { tags.optString(it).takeIf(String::isNotBlank) }
                ?: kinds.first { it.id==kind }.title
            val id = "$type/${e.getLong("id")}"
            places[id] = EssentialPlace(id,kind,name.take(200),point,detail.toString())
        }
        return EssentialsSnapshot(timestamp,places.values.toList())
    }
    fun details(place: EssentialPlace): String {
        val d = JSONObject(place.details)
        return buildList {
            val address = listOf("addr:housenumber","addr:street","addr:city","addr:postcode").map { d.optString(it) }.filter(String::isNotBlank).joinToString(" ")
            add("Address: ${address.ifBlank { "not recorded" }}")
            add("Hours in snapshot: ${d.optString("opening_hours").ifBlank { "not recorded" }}")
            add("Wheelchair access: ${d.optString("wheelchair").ifBlank { "not recorded" }}")
            listOf("emergency" to "Emergency department", "phone" to "Phone", "contact:phone" to "Contact phone", "fee" to "Fee", "access" to "Access", "check_date" to "Listing checked", "survey:date" to "Survey date").forEach { (key,label) ->
                d.optString(key).takeIf(String::isNotBlank)?.let { add("$label: $it") }
            }
        }.joinToString("\n")
    }
}
