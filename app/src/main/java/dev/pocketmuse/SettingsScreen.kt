package dev.pocketmuse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(vm: AssistantViewModel, onNavigate: (String) -> Unit) {
    val skills by vm.skills.collectAsState()
    val models by vm.models.collectAsState()
    val connections by vm.connections.collectAsState()
    val profile by vm.youProfile.collectAsState()
    val home by vm.homeState.collectAsState()
    Page("Settings", "A few choices to make Cina yours.") {
        NavigationRow("About you", "Profile and response preferences") { onNavigate("You") }
        NavigationRow("Responses", "Speed, model choices & response timings") { onNavigate("Response settings") }
        NavigationRow("Models", "${models.size} installed · downloads & access", { onNavigate("Models") })
        NavigationRow("Skills", "${skills.count { it.enabled }} enabled · familiar tasks & instructions", { onNavigate("Skills") })
        NavigationRow("Connections", "${connections.count { it.connected } + if(home.paired) 1 else 0} connected · services & tools", { onNavigate("Connections") })
        NavigationRow("Appearance", "${profile.petName.ifBlank { "Cina" }} · appearance & name", { onNavigate("Companion") })
        NavigationRow("Chat & permissions", "New chat defaults & action approvals", { onNavigate("Chat preferences") })
        if(BuildConfig.INBOX_ENABLED) NavigationRow("Captured library backup", "Storage, cleanup & encrypted source backups") { onNavigate("Library storage") }
        NavigationRow("About & updates", "Version ${BuildConfig.VERSION_NAME}", { onNavigate("About") })
        Text("Conversations and personal details are stored on this phone. Home receives chat excerpts and attached passages when you choose a Home model. Connected services receive data when you use them.",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun ResponseSettings(vm: AssistantViewModel, onModels: () -> Unit) {
    val mode by vm.responseMode.collectAsState()
    val home by vm.homeState.collectAsState()
    val samples by vm.performanceSamples.collectAsState()
    var showTimings by remember { mutableStateOf(false) }
    SectionTitle("Response mode")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResponseMode.entries.forEach { choice ->
            FilterChip(selected = mode == choice, onClick = { vm.setResponseMode(choice) }, label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    Text("Auto uses Quick for ordinary requests and Deep for complex work. Quick disables thinking when the model supports it. Both modes can use the same model.", color = Muted, fontSize = 12.sp)
    TextButton(onClick = onModels) { Text("Choose Phone, Heavy phone and Home models") }
    TextButton(onClick = { showTimings = true }) { Text("Response timings · ${samples.size} recent runs") }
    if(home.deepModel != null) Text("Larger analysis can use Home when connected. Your heavy phone model remains available offline.", color = Muted, fontSize = 12.sp)
    if (showTimings) AlertDialog(onDismissRequest = { showTimings = false }, title = { Text("Local response timings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Milestones are measured from Send. Prompt processing is a duration. No message content is recorded.")
            if(samples.isEmpty()) Text("Send a request to collect a sample.")
            samples.forEach { Text(it, fontSize = 12.sp); HorizontalDivider() }
        }
    }, confirmButton = { TextButton(onClick = { showTimings = false }) { Text("Close") } }, dismissButton = { TextButton(onClick = vm::clearPerformance) { Text("Clear") } })
}

@Composable
internal fun ChatPreferencesScreen(vm: AssistantViewModel) {
    val defaultWeb by vm.defaultWeb.collectAsState()
    val defaultYolo by vm.defaultYolo.collectAsState()
    val alwaysAllowed by vm.alwaysAllowed.collectAsState()
    var brave by remember { mutableStateOf("") }
    Page("Chat & permissions", "Choose how new conversations start.") {
        SettingToggle("Web search", "Search the internet in new chats", defaultWeb, vm::setDefaultWeb)
        SettingToggle("Run actions without asking", "YOLO mode for new chats", defaultYolo, vm::setDefaultYolo)
        Text("Automatic actions turn off when you leave the app or switch chats.", color = Muted, fontSize = 12.sp)
        HorizontalDivider(color = Line)
        SectionTitle("Always allowed tools")
        if (alwaysAllowed.isEmpty()) Text("Cina asks before actions that create or share data.", color = Muted, fontSize = 13.sp)
        alwaysAllowed.sorted().forEach { key ->
            PaperRow(key.removePrefix("local:").removePrefix("mcp:").replace(":", " · "), "Runs without a confirmation", "Ask again") { vm.revokeAlwaysAllowed(key) }
        }
        HorizontalDivider(color = Line)
        SectionTitle("Web search fallback")
        Text("DuckDuckGo works without a key. Optionally add a Brave key for when it is unavailable.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(brave, { brave = it }, label = { Text("Brave Search API key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setKey("brave", brave); brave = "" }, enabled = brave.isNotBlank()) { Text("Save key") }
    }
}

@Composable
internal fun AboutScreen(updateStatus: String, onCheckUpdates: () -> Unit) {
    Page("About Cina", "A little space to think, right on your phone.") {
        SectionTitle("App updates")
        Text("Version ${BuildConfig.VERSION_NAME}", color = Muted)
        Button(onClick = onCheckUpdates) { Text("Check for updates") }
        if (updateStatus.isNotBlank()) Text(updateStatus, color = Muted, fontSize = 13.sp)
        HorizontalDivider(color = Line)
        Text("Cina runs text models on your device. Web search and connected tools use the network when you enable them.", color = Muted, fontSize = 14.sp)
    }
}

@Composable
internal fun ConnectionsScreen(vm: AssistantViewModel) {
    val activity = checkNotNull(LocalActivity.current) as ComponentActivity
    val connections by vm.connections.collectAsState()
    val home by vm.homeState.collectAsState()
    var expandedConnection by rememberSaveable { mutableStateOf<String?>(null) }
    var mcpToken by remember { mutableStateOf("") }
    var customName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var customToken by remember { mutableStateOf("") }
    var showAvailable by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val states by vm.connectionStates.collectAsState()
    LaunchedEffect(states) {
        if(expandedConnection != null && states[expandedConnection]?.startsWith("Connected ·") == true) { expandedConnection = null; mcpToken = "" }
        if(showCustom && states["custom"]?.startsWith("Connected ·") == true) { showCustom = false; customName = ""; customUrl = ""; customToken = "" }
    }
    Page("Connections", "Bring your services into a conversation when you need them.") {
        Text("Connect a service to let Cina use its available tools. Actions ask before they run unless you always allow that tool or enable YOLO mode.", color = Muted, fontSize = 13.sp)
        Text("Connect Google or Linear with your account. Google asks for access only to the service you choose.", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Your connections")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAvailable = !showAvailable }) { Text(if (showAvailable) "Hide available" else "+ Add") }
        }
        HomeConnectionRow(vm, showAvailable)
        if (!home.paired && connections.none { it.connected || it.preset.id.startsWith("custom_") }) Text("No services connected. Add one when you need it.", color = Muted)
        if (showAvailable) {
            SectionTitle("Sign in ready")
            Text("Choose a service, select your account, and approve access.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if(showAdvanced) "Hide advanced setup" else "Advanced · tokens & custom servers") }
            if(showAdvanced) TextButton(onClick = { showCustom = true }) { Text("Add a custom MCP server") }
        }
        connections.filter { it.connected || it.preset.id.startsWith("custom_") || (showAvailable && (GoogleService.supports(it.preset.id) || it.preset.id == "linear" || showAdvanced)) }.sortedByDescending { it.connected }.forEach { connection ->
            val preset = connection.preset
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.title, color = Ink, fontWeight = FontWeight.SemiBold)
                        connection.account?.let {
                            Text(it, color = Muted, fontSize = 12.sp)
                            Text(GoogleService.accessLabel(preset.id), color = Muted, fontSize = 11.sp)
                        }
                        if(preset.id.startsWith("custom_")) Text(preset.url, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(states[preset.id] ?: if(connection.connected && connection.toolCount > 0) "Connected · ${connection.toolCount} tools" else if(connection.connected) "Reconnect to load tools" else "Not connected", color = Muted, fontSize = 12.sp)
                    }
                    if(connection.connected) TextButton(onClick = { vm.disconnectMcp(preset.id); expandedConnection = null; mcpToken = "" }, enabled = states.values.none { it == "Waiting for Google…" || it == "Connecting…" }) { Text("Disconnect") }
                    TextButton(onClick = { expandedConnection = if(expandedConnection == preset.id) null else preset.id; mcpToken = "" }) {
                        Text(if(connection.connected) "Reconnect" else if(preset.id == "linear" || GoogleService.supports(preset.id)) "Sign in" else "Set up")
                    }
                }
                if(preset.id.startsWith("custom_")) TextButton(onClick = { vm.removeMcpServer(preset.id); expandedConnection = null; mcpToken = "" }) { Text("Remove server") }
                if(expandedConnection == preset.id) {
                    if (GoogleService.supports(preset.id)) {
                        Text(GoogleService.accessLabel(preset.id), color = Muted, fontSize = 12.sp)
                        Button(onClick = { (activity as MainActivity).connectGoogle(preset.id) }, enabled = states.values.none { it == "Waiting for Google…" || it == "Connecting…" }) { Text("Connect with Google") }
                        Text("Disconnect stops Cina's access on this device. To revoke Google's permission for Cina across services, visit your Google Account connections.", color = Muted, fontSize = 12.sp)
                        TextButton(onClick = { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://myaccount.google.com/connections"))) }) { Text("Manage Google permissions") }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced options" else "Advanced options") }
                    }
                    if (!GoogleService.supports(preset.id) || showAdvanced) {
                        if(preset.id == "linear") {
                            Button(onClick = { vm.startLinearSignIn(activity) }) { Text("Sign in with Linear") }
                            Text("Or connect with an API key or existing OAuth token", color = Muted, fontSize = 12.sp)
                        } else Text(preset.hint, color = Muted, fontSize = 12.sp)
                        if (GoogleService.supports(preset.id)) Text("Legacy hosted MCP connection. Requires your own MCP setup and manual token renewal.", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text(if(preset.id == "linear") "API key or access token" else if(preset.id.startsWith("custom_")) "Bearer token (if needed)" else "Access token") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { vm.connectMcp(preset.id, mcpToken) }, enabled = states.values.none { it == "Waiting for Google…" || it == "Connecting…" } && (mcpToken.isNotBlank() || preset.id.startsWith("custom_"))) { Text(if(states[preset.id] == "Connecting…") "Connecting…" else "Connect") }
                    }
                }
            }
        }
    }
    if (showCustom) AlertDialog(onDismissRequest = { showCustom = false }, containerColor = Paper,
        title = { Text("Add MCP server") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            states["custom"]?.takeIf { !it.startsWith("Connected ·") }?.let { Text(it, color = if(it.startsWith("Could not")) MaterialTheme.colorScheme.error else Muted) }
            Text("Use a hosted HTTPS MCP endpoint. Local command servers cannot run on Android.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(customName, { customName = it }, label = { Text("Server name") }, singleLine = true)
            OutlinedTextField(customUrl, { customUrl = it }, label = { Text("HTTPS URL") }, singleLine = true)
            OutlinedTextField(customToken, { customToken = it }, label = { Text("Bearer token (optional)") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { vm.addMcpServer(customName, customUrl, customToken) },
            enabled = states["custom"] != "Connecting…" && customName.isNotBlank() && customUrl.startsWith("https://")) { Text(if(states["custom"] == "Connecting…") "Connecting…" else "Connect") } },
        dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } })
}
