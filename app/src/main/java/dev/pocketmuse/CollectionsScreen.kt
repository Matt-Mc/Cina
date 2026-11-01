package dev.pocketmuse

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CollectionsScreen(vm: CollectionsViewModel = viewModel()) {
    DisposableEffect(vm) { vm.startObserving();onDispose { vm.stopObserving() } }
    val state by vm.state.collectAsState()
    val uri=LocalUriHandler.current
    var city by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableIntStateOf(5) }
    var category by rememberSaveable { mutableStateOf("pharmacy") }
    var remove by remember { mutableStateOf<SavedCollection?>(null) }
    var addingArea by rememberSaveable { mutableStateOf(false) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true || result[Manifest.permission.ACCESS_COARSE_LOCATION]==true) vm.useLocation() else vm.locationDenied()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=22.dp).testTag("collections-list"),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item {
            Text("Collections",style=MaterialTheme.typography.headlineLarge,color=Ink)
            Text("Useful sources, ready without a connection.",color=Muted)
        }
        item {
            Text(CollectionCatalog.essentials.title,style=MaterialTheme.typography.titleLarge)
            Text(CollectionCatalog.essentials.description)
            Text("Download places around a city. Search and place details then work offline, without a model.",color=Muted)
        }
        if(state.saved.isNotEmpty()) item { OutlinedButton(onClick={ addingArea=!addingArea }) { Text(if(addingArea) "Close area download" else "Download another area") } }
        if(state.saved.isEmpty() || addingArea) {
        item {
            OutlinedTextField(city,{ city=it;title="";lat="";lon="";vm.clearCity() },label={ Text("City and country") },placeholder={ Text("e.g. Ottawa, Canada") },enabled=!state.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={ title="";lat="";lon="";vm.findCity(city) },enabled=!state.busy && city.isNotBlank()) { Text(if(state.busy) "Finding city…" else "Find city") }
            Text("Find your city online, then choose how far around it to download.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        items(state.choices,key={ "${it.point.lat},${it.point.lon}" }) { choice ->
            OutlinedButton(onClick={ title=choice.title;lat=choice.point.lat.toString();lon=choice.point.lon.toString() },modifier=Modifier.fillMaxWidth()) { Text(choice.title) }
        }
        item {
            if(title.isNotBlank()) Text("Selected: $title")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(2,5,10,20).forEach { km -> FilterChip(selected=radius==km,onClick={ radius=km },label={ Text("$km km radius") }) }
            }
            Text("Size depends on the area; downloads are capped at 12 MB. Larger or busy areas may need a smaller radius.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Button(onClick={ vm.download(title,GeoPoint(lat.toDouble(),lon.toDouble()),radius) },enabled=!state.busy && !state.downloadActive && title.isNotBlank() && lat.isNotBlank() && lon.isNotBlank()) { Text("Download Essentials area") }
            Text("City search uses your device’s geocoding service. Downloads share the selected city’s centre with Private.coffee, or overpass-api.de if a retry is needed.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        }
        if(state.busy || state.downloadActive) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.downloadActive) TextButton(onClick=vm::cancel) { Text("Cancel download") }
        }
        if(state.message.isNotBlank()) item { Text(state.message) }
        if(state.error.isNotBlank()) item { Text(state.error,color=MaterialTheme.colorScheme.error) }
        item { HorizontalDivider();Text("On this phone",style=MaterialTheme.typography.titleLarge) }
        if(state.saved.isEmpty()) item { Text("No areas downloaded yet. Choose an area above while online.",color=Muted) }
        items(state.saved,key={ it.id }) { saved ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(saved.title,style=MaterialTheme.typography.titleMedium)
                Text("${saved.count} ${if(saved.count==1) "place" else "places"} · ${saved.radiusKm} km radius · ${if(saved.bytes<1024) "${saved.bytes} bytes" else "${saved.bytes/1024} KB"} source download")
                Text("Map snapshot: ${saved.snapshot}\nDownloaded: ${DateFormat.getDateTimeInstance().format(Date(saved.downloaded))}",style=MaterialTheme.typography.bodySmall,color=Muted)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked=saved.enabled,onCheckedChange={ vm.enable(saved.id,it) },enabled=!state.busy)
                    Text("Use in offline searches")
                }
                TextButton(onClick={ vm.choose(saved.id) },enabled=saved.enabled && !state.busy) { Text("Search from this area's centre") }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={ vm.download(saved.title,saved.centre,saved.radiusKm,saved.id) },enabled=!state.downloadActive && !state.busy) { Text("Refresh online") }
                    TextButton(onClick={ remove=saved },enabled=!state.downloadActive && !state.busy) { Text("Remove") }
                }
            } }
        }
        if(state.saved.isNotEmpty()) {
            item {
                HorizontalDivider();Text("Find essential places",style=MaterialTheme.typography.titleLarge)
                Text("Choose an area's centre above, or use your phone location within that area. Distances are straight-line, not walking or driving routes.",color=Muted)
                TextButton(onClick={ permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) },enabled=!state.busy) { Text("Use current phone location") }
                Text("Phone location stays on this device and expires after 10 minutes.",style=MaterialTheme.typography.bodySmall,color=Muted)
                if(state.referenceNotice.isNotBlank()) Text(state.referenceNotice,color=Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Essentials.kinds.forEach { kind -> FilterChip(selected=category==kind.id,onClick={ category=kind.id },label={ Text(kind.title) }) }
                }
                Button(onClick={ vm.search(category) },enabled=!state.busy) { Text("Find offline") }
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.error.isNotBlank()) Text(state.error,color=MaterialTheme.colorScheme.error)
            }
            state.results?.let { result ->
                item {
                    Text("From ${result.referenceLabel}",style=MaterialTheme.typography.titleSmall)
                    Text("${result.collection.title} only · snapshot ${result.collection.snapshot}. Listings can be missing or outdated.",color=Muted)
                    if(result.hits.isEmpty()) Text("No matching records in this area. Other places may exist.")
                }
                items(result.hits,key={ "result:${it.place.id}" }) { hit ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(hit.place.name,style=MaterialTheme.typography.titleMedium)
                        Text("${String.format(Locale.getDefault(),"%.1f",hit.metres/1000)} km straight-line")
                        Text(Essentials.details(hit.place))
                        Text("${hit.place.point.lat}, ${hit.place.point.lon}",color=Muted)
                        TextButton(onClick={ uri.openUri(hit.place.source) }) { Text("View OpenStreetMap source (online)") }
                    } }
                }
            }
        }
        item {
            Text("© OpenStreetMap contributors · ODbL 1.0",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("Source and licence") }
            Text("Hours are shown as recorded, not checked for current opening. More collection types can be added in future releases.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    remove?.let { saved -> AlertDialog(onDismissRequest={ remove=null },title={ Text("Remove ${saved.title}?") },text={ Text("Its downloaded places will no longer be available offline. You can download this area again later.") },
        confirmButton={ TextButton(onClick={ vm.delete(saved.id);remove=null }) { Text("Remove") } },dismissButton={ TextButton(onClick={ remove=null }) { Text("Keep") } }) }
}
