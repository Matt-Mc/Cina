package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndpointScreen(vm: AssistantViewModel) {
    val endpoint by vm.endpoint.collectAsState()
    val busy by vm.busy.collectAsState()
    var url by rememberSaveable { mutableStateOf(endpoint.url) }
    var model by rememberSaveable { mutableStateOf(endpoint.model) }
    var key by remember { mutableStateOf(vm.secrets.get("inference_endpoint_key").orEmpty()) }
    var provider by rememberSaveable { mutableStateOf(if(endpoint.url.contains(":8080")) "llama.cpp" else "Ollama") }
    var help by rememberSaveable { mutableStateOf(false) }
    val links = LocalUriHandler.current
    val addressError = if(url.isBlank()) null else runCatching { ModelEndpoint.normalize(url) }.exceptionOrNull()?.message
    val editing = !busy && !endpoint.checking
    Page("Model server", "Connect to Ollama or llama.cpp on your computer.") {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Ollama","llama.cpp").forEach { name ->
                FilterChip(selected=provider==name,onClick={ provider=name },label={ Text(name) })
            }
        }
        OutlinedTextField(url, { url=it;vm.clearEndpointCheck() },label={ Text("Server URL") },
            placeholder={ Text(if(provider=="Ollama") "http://192.168.1.20:11434" else "http://192.168.1.20:8080") },
            supportingText={ Text(addressError ?: "Use your computer's network address. localhost refers to this phone.") },
            isError=addressError!=null,enabled=editing,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(key,{ key=it;vm.clearEndpointCheck() },label={ Text("API key (optional)") },
            visualTransformation=PasswordVisualTransformation(),enabled=editing,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={ vm.checkEndpoint(url,key) },enabled=editing && url.isNotBlank() && addressError==null) {
            Text(if(endpoint.checking) "Connecting…" else "Connect & load models")
        }
        if(endpoint.message.isNotBlank()) Text(endpoint.message,color=if(endpoint.error) MaterialTheme.colorScheme.error else Muted)
        OutlinedTextField(model,{ model=it },label={ Text("Model ID") },supportingText={ Text("Choose a returned model or enter its exact ID.") },
            enabled=editing,singleLine=true,modifier=Modifier.fillMaxWidth())
        if(endpoint.models.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            endpoint.models.forEach { name -> FilterChip(selected=model==name,onClick={ model=name },label={ Text(name) }) }
        }
        Text("Server chats send recent messages and attached passages to your computer. They cannot run actions. Scheduled prompts and offline collections stay on your phone.",color=Muted,style=MaterialTheme.typography.bodyMedium)
        if(url.trim().startsWith("http://")) Text("HTTP is unencrypted. Use a trusted private network or an HTTPS address.",color=Muted,style=MaterialTheme.typography.bodyMedium)
        Button(onClick={ vm.saveEndpoint(url,model,key) },enabled=editing && url.isNotBlank() && addressError==null && model.isNotBlank()) { Text("Save & use server") }
        if(endpoint.enabled) TextButton(onClick={ vm.useEndpoint(false) },enabled=editing) { Text("Switch to phone model") }
        TextButton(onClick={ help=!help }) { Text(if(help) "Hide connection help" else "Connection help") }
        if(help) {
            SectionTitle("Connect from your phone")
            Text("Keep both devices on the same trusted network. Allow the server port through your computer's firewall for private networks.",color=Muted)
            if(provider=="Ollama") {
                SelectionContainer { Text("On your computer, set OLLAMA_HOST to 0.0.0.0:11434 and restart Ollama. Install a model there before loading the model list.",color=Ink) }
                TextButton(onClick={ links.openUri("https://docs.ollama.com/faq#how-do-i-configure-ollama-server") }) { Text("Ollama setup guide") }
            } else {
                Text("Start llama.cpp with your downloaded model:",color=Muted)
                SelectionContainer { Text("llama-server -m model.gguf --host 0.0.0.0 --port 8080",color=Ink) }
                TextButton(onClick={ links.openUri("https://github.com/ggml-org/llama.cpp/tree/master/tools/server") }) { Text("llama.cpp server guide") }
            }
            Text("Use the computer's IP address in Server URL, not 0.0.0.0. If connection fails, check the address, port, firewall and whether the server is running.",color=Muted)
        }
        if(endpoint.url.isNotBlank()) TextButton(onClick={ vm.removeEndpoint();url="";model="";key="" },enabled=editing) { Text("Remove saved server") }
    }
}
