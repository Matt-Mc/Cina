package dev.pocketmuse

import android.app.Application
import android.annotation.SuppressLint
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.resume
import java.util.UUID

internal data class AreaChoice(val title: String,val point: GeoPoint)
internal data class CollectionsUiState(val saved: List<SavedCollection> = emptyList(), val choices: List<AreaChoice> = emptyList(),
    val busy: Boolean = false,val downloadActive: Boolean = false,val message: String = "",val error: String = "",val results: PlaceSearch? = null, val referenceNotice: String = "")

internal class CollectionsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable=MutableStateFlow(CollectionsUiState())
    val state=mutable.asStateFlow()
    private val work=WorkManager.getInstance(application)
    private var observer: Job? = null
    fun startObserving() {
        if(observer?.isActive==true) return
        observer=viewModelScope.launch {
        while(isActive) {
            try {
                val (saved,download)=withContext(Dispatchers.IO) {
                    val saved=CollectionRepository(getApplication()).use { it.list() }
                    saved to CollectionDownloadWorker.currentWork(getApplication())
                }
                val active=download?.state?.isFinished==false
                val message=when {
                    active && download?.state==WorkInfo.State.ENQUEUED && download.runAttemptCount>0 -> "Map service was unavailable. Waiting to try a backup server; your saved area is unchanged."
                    active -> download?.progress?.getString("message") ?: "Waiting for a network connection…"
                    download?.state==WorkInfo.State.FAILED -> download.outputData.getString("message").orEmpty()
                    download?.state==WorkInfo.State.SUCCEEDED -> download.outputData.getString("message").orEmpty()
                    download?.state==WorkInfo.State.CANCELLED -> "Download cancelled. Any previous saved area is still available."
                    else -> ""
                }
                mutable.update { it.copy(saved=saved,downloadActive=active,message=message) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error=e.message ?: "Could not read collections.") } }
            delay(1000)
        }
        }
    }
    fun stopObserving() { observer?.cancel();observer=null }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        if(mutable.value.busy) return@launch
        mutable.update { it.copy(busy=true,error="") }
        try { withContext(Dispatchers.IO) { block() } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { mutable.update { it.copy(error=e.message ?: "Could not complete that action.",results=null) } }
        finally { mutable.update { it.copy(busy=false) } }
    } }
    @Suppress("DEPRECATION")
    fun findCity(query: String) = action {
        mutable.update { it.copy(choices=emptyList()) }
        require(query.isNotBlank()) { "Enter a city or area name." }
        require(Geocoder.isPresent()) { "City search is unavailable on this device. Existing downloaded areas still work offline." }
        val matches=Geocoder(getApplication()).getFromLocationName(query.take(200),5).orEmpty().filter { it.hasLatitude() && it.hasLongitude() }
        require(matches.isNotEmpty()) { "No matching city found. Check the spelling and include the country, then try again online." }
        val choices=matches.map { AreaChoice((it.getAddressLine(0) ?: query).take(160),GeoPoint(it.latitude,it.longitude)) }.distinctBy { it.point }
        mutable.update { it.copy(choices=choices) }
    }
    fun clearCity() { mutable.update { it.copy(choices=emptyList(),error="") } }
    fun download(title: String,point: GeoPoint,radius: Int,id: String = UUID.randomUUID().toString()) = action {
        CollectionDownloadWorker.enqueue(getApplication(),id,title.trim(),point,radius)
        mutable.update { it.copy(downloadActive=true) }
    }
    fun cancel() { work.cancelUniqueWork(CollectionDownloadWorker.TAG) }
    fun enable(id: String,value: Boolean)=action { CollectionRepository(getApplication()).use { it.enable(id,value) };mutable.update { it.copy(results=null) } }
    fun delete(id: String)=action {
        require(!mutable.value.downloadActive) { "Cancel the active download before removing an area." }
        CollectionRepository(getApplication()).use { it.delete(id) };mutable.update { it.copy(results=null,referenceNotice="") }
    }
    fun choose(id: String)=action {
        val title=CollectionRepository(getApplication()).use { it.choose(id);it.list().first { area -> area.id==id }.title }
        mutable.update { it.copy(results=null,referenceNotice="Using $title's download centre for searches.") }
    }
    fun search(category: String)=action { val result=CollectionRepository(getApplication()).use { it.search(category) };mutable.update { it.copy(results=result) } }
    fun locationDenied() { mutable.update { it.copy(error="Location permission was not granted. You can use a downloaded area's centre instead.") } }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun useLocation()=action {
        val manager=getApplication<Application>().getSystemService(LocationManager::class.java)
        val fine=getApplication<Application>().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val provider=when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> error("Turn on device location with an available provider, or use the downloaded area's centre.")
        }
        val location=withTimeoutOrNull(25_000) { suspendCancellableCoroutine<Location> { continuation ->
            val listener=object: LocationListener {
                override fun onLocationChanged(location: Location) { manager.removeUpdates(this);if(continuation.isActive) continuation.resume(location) }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) {}
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestSingleUpdate(provider,listener,Looper.getMainLooper())
        } } ?: error("No location fix received. Try outdoors or use the area centre.")
        CollectionRepository(getApplication()).use { it.reference(GeoPoint(location.latitude,location.longitude),"phone location captured at ${java.time.Instant.now()} (accuracy ${location.accuracy.toInt()} m; expires after 10 minutes)",true) }
        mutable.update { it.copy(results=null,referenceNotice="Using phone location for 10 minutes. Find offline to search the selected area.") }
    }
}
