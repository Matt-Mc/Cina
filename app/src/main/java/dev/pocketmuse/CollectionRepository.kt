package dev.pocketmuse

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.Locale

internal data class SavedCollection(val id: String, val kind: String, val title: String, val centre: GeoPoint,
    val radiusKm: Int, val snapshot: String, val downloaded: Long, val bytes: Long, val count: Int, val enabled: Boolean)
internal data class PlaceHit(val place: EssentialPlace, val metres: Double)
internal data class PlaceSearch(val collection: SavedCollection, val reference: GeoPoint, val referenceLabel: String, val hits: List<PlaceHit>)

/** Separate database keeps downloaded public data out of personal memories and library backups. */
internal class CollectionRepository(context: Context) : SQLiteOpenHelper(context.applicationContext,"collections.db",null,1) {
    private val preferences = context.applicationContext.getSharedPreferences("collection-reference",Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE collections(id TEXT PRIMARY KEY,kind TEXT NOT NULL,format_version INTEGER NOT NULL,title TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,radius_km INTEGER NOT NULL,snapshot TEXT NOT NULL,downloaded INTEGER NOT NULL,bytes INTEGER NOT NULL,count INTEGER NOT NULL,enabled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE places(collection_id TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,name TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,details TEXT NOT NULL,PRIMARY KEY(collection_id,id))")
        db.execSQL("CREATE INDEX places_category ON places(collection_id,kind)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported collection database version.")
    fun list(): List<SavedCollection> = readableDatabase.rawQuery("SELECT id,kind,title,lat,lon,radius_km,snapshot,downloaded,bytes,count,enabled FROM collections ORDER BY title,id",null).use { c ->
        buildList { while(c.moveToNext()) add(SavedCollection(c.getString(0),c.getString(1),c.getString(2),GeoPoint(c.getDouble(3),c.getDouble(4)),c.getInt(5),c.getString(6),c.getLong(7),c.getLong(8),c.getInt(9),c.getInt(10)!=0)) }
    }
    fun install(id: String, title: String, centre: GeoPoint, radiusKm: Int, snapshot: EssentialsSnapshot, bytes: Long, checkCancelled: () -> Unit = {}) {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && title.isNotBlank() && title.length<=160 && radiusKm in 1..20)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val enabled = list().firstOrNull { it.id==id }?.enabled ?: true
            db.delete("places","collection_id=?",arrayOf(id))
            val statement=db.compileStatement("INSERT INTO places VALUES(?,?,?,?,?,?,?)")
            statement.use { s -> snapshot.places.forEach { p ->
                checkCancelled()
                s.bindString(1,id);s.bindString(2,p.id);s.bindString(3,p.kind);s.bindString(4,p.name)
                s.bindDouble(5,p.point.lat);s.bindDouble(6,p.point.lon);s.bindString(7,p.details);s.executeInsert()
            } }
            val values=ContentValues().apply {
                put("id",id);put("kind",CollectionCatalog.essentials.id);put("format_version",1);put("title",title)
                put("lat",centre.lat);put("lon",centre.lon);put("radius_km",radiusKm);put("snapshot",snapshot.timestamp)
                put("downloaded",System.currentTimeMillis());put("bytes",bytes);put("count",snapshot.places.size);put("enabled",if(enabled) 1 else 0)
            }
            checkCancelled()
            db.insertWithOnConflict("collections",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun enable(id: String, enabled: Boolean) { writableDatabase.update("collections",ContentValues().apply { put("enabled",if(enabled) 1 else 0) },"id=?",arrayOf(id)) }
    fun delete(id: String) {
        val db=writableDatabase;db.beginTransaction()
        try { db.delete("places","collection_id=?",arrayOf(id));db.delete("collections","id=?",arrayOf(id));db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        if(preferences.getString("area",null)==id) preferences.edit().clear().apply()
    }
    fun choose(id: String) { require(list().any { it.id==id });preferences.edit().clear().putString("area",id).apply() }
    fun reference(point: GeoPoint, label: String, current: Boolean) {
        preferences.edit().putString("lat",point.lat.toString()).putString("lon",point.lon.toString()).putString("label",label)
            .putLong("expires",if(current) System.currentTimeMillis()+10*60*1000 else Long.MAX_VALUE).apply()
    }
    fun search(category: String, area: String = "", emergencyOnly: Boolean = false, limit: Int = 20): PlaceSearch {
        require(category in Essentials.kinds.map { it.id }) { "Choose a supported Essentials category." }
        val collections=list().filter { it.enabled && it.kind==CollectionCatalog.essentials.id }
        require(collections.isNotEmpty()) { "No enabled Essentials areas. Download one in Library → Collections." }
        val matches=collections.filter { it.id==area || it.title.equals(area,true) || it.title.substringBefore(',').equals(area,true) }
        val selected=if(area.isNotBlank()) matches.singleOrNull()
            else collections.firstOrNull { it.id==preferences.getString("area",null) } ?: collections.singleOrNull()
        requireNotNull(selected) { "Choose an exact downloaded area: ${collections.joinToString { it.title }}. Other areas are not covered." }
        val useReference=area.isBlank() && preferences.getLong("expires",0)>System.currentTimeMillis()
        val reference=if(useReference) GeoPoint(preferences.getString("lat",null)!!.toDouble(),preferences.getString("lon",null)!!.toDouble()) else selected.centre
        val label=if(useReference) preferences.getString("label","")!! else "${selected.title} download centre (not your current location)"
        require(selected.centre.distanceTo(reference)<=selected.radiusKm*1000) { "The selected reference is outside ${selected.title}'s downloaded area. Choose another area or its centre in Collections." }
        val hits=readableDatabase.rawQuery("SELECT id,kind,name,lat,lon,details FROM places WHERE collection_id=? AND kind=?",arrayOf(selected.id,category)).use { c ->
            buildList { while(c.moveToNext()) {
                val p=EssentialPlace(c.getString(0),c.getString(1),c.getString(2),GeoPoint(c.getDouble(3),c.getDouble(4)),c.getString(5))
                if(!emergencyOnly || JSONObject(p.details).optString("emergency")=="yes") add(PlaceHit(p,reference.distanceTo(p.point)))
            } }.sortedBy { it.metres }.take(limit.coerceIn(1,50))
        }
        return PlaceSearch(selected,reference,label,hits)
    }
    fun answer(query: String, area: String = ""): String {
        val categories=Essentials.categories(query)
        require(categories.size==1) { "Search one category at a time: ${Essentials.kinds.joinToString { it.title }}." }
        // Preserve an explicitly named location even when a small model omits the optional area field.
        val mentioned=Regex("\\b(?:in|near|around)\\s+(.+?)[?.!]*$",RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)?.trim()
            ?.takeUnless { it.lowercase() in setOf("me","here","my location","this area") }
        val result=search(categories.single(),mentioned ?: area,query.contains("emergency",true),5)
        return buildString {
            append("Offline Essentials · ${result.collection.title}. Map snapshot: ${result.collection.snapshot}.\n")
            append("Distances are straight-line from ${result.referenceLabel}, not routes. Only this downloaded ${result.collection.radiusKm} km area was searched.\n")
            append("© OpenStreetMap contributors · ODbL 1.0 · https://www.openstreetmap.org/copyright\n")
            append("Listings may be incomplete or stale. Hours are source text, not proof of being open now. Missing fields are unknown.\n")
            if(result.hits.isEmpty()) append("No matching records in this snapshot. This does not mean no such places exist.")
            result.hits.forEach { hit -> append("\n${hit.place.name} — ${String.format(Locale.ROOT,"%.1f",hit.metres/1000)} km\n${Essentials.details(hit.place)}\nSource: ${hit.place.source}\n") }
        }
    }
}
