package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.gen.LlmDiscoverModelsResultItem
import dsh.gen.LlmListConfigurableProvidersResultItem
import dsh.gen.LlmModelDiscoveryRequest
import dsh.gen.ModelCatalog
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.obj
import dsh.transport.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Server settings (`settings/describe` + revision-checked `settings/mutate`). */
class SettingsModel(private val client: DshClient, private val scope: CoroutineScope) {
    private val _views = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val views: StateFlow<Map<String, JsonObject>> = _views
    private val _writable = MutableStateFlow(true)
    val writable: StateFlow<Boolean> = _writable
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    suspend fun load() {
        val d = client.guard("Load settings") { client.conn.callRaw("settings/describe", JsonObject(emptyMap())) }
        _loading.value = false
        if (d == null) return
        _writable.value = d["writable"].bool != false
        _views.value = d["namespaces"].arr.orEmpty().mapNotNull { n -> n.obj?.let { o -> (o["ns"].str ?: return@mapNotNull null) to o } }.toMap()
    }

    fun mutate(ns: String, ops: List<JsonObject>) {
        val view = _views.value[ns] ?: return
        scope.launch {
            val r = try {
                client.conn.callRaw("settings/mutate", buildJsonObject {
                    put("ns", ns); put("ops", JsonArray(ops)); put("expectedRevision", view["revision"] ?: JsonPrimitive(0))
                })
            } catch (e: dsh.transport.DshException) {
                client.notice(if (e.code.contains("revision") || e.code.contains("conflict")) "Settings changed elsewhere — reloaded" else "Save failed: ${e.message}")
                load(); return@launch
            }
            // The response is the updated namespace view; keep the schema if the server omitted it.
            val updated = r.obj ?: return@launch
            _views.value = _views.value + (ns to JsonObject(view + updated))
        }
    }

    fun actions(ns: String) = object : SettingsEditActions {
        override fun set(path: List<String>, value: JsonElement) =
            mutate(ns, listOf(buildJsonObject { put("op", "set"); put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } }); put("value", value) }))
        override fun unset(path: List<String>) =
            mutate(ns, listOf(buildJsonObject { put("op", "unset"); put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } }) }))
    }

    fun secrets(view: JsonObject?): Map<List<String>, Boolean> =
        view["secrets"].arr.orEmpty().associate { s -> s["path"].arr.orEmpty().mapNotNull { it.str } to (s["set"].bool == true) }
}

private val NS_TITLES = mapOf(
    "agent-default-model" to "Default model for new sessions",
    "llm-pi-ai" to "Model providers (all)",
    "llm-deepseek" to "DeepSeek API",
    "llm-deepseek-account" to "DeepSeek account models",
    "bash-sandbox" to "Shell & sandbox",
    "permission" to "Permissions",
    "subagent" to "Subagents",
    "subagent-model-selection-settings" to "Subagent model selection",
    "web-search-deepseek" to "Web search",
    "agent-loop" to "Agent loop",
    "agent-preset-registry" to "Agent presets",
    "locale" to "Language",
    "ui-theme" to "Web UI · theme",
    "ui-settings" to "Web UI · settings",
    "ui-settings-general" to "Web UI · general",
    "ui-settings-account" to "Web UI · account",
    "ui-conversation" to "Web UI · conversation",
    "ui-chat" to "Web UI · chat",
)

/** Server-side settings, appended to the Settings screen. */
@Composable
fun ServerSettingsSections(client: DshClient) {
    val scope = rememberCoroutineScope()
    val model = remember(client) { SettingsModel(client, client.scope) }
    val views by model.views.collectAsState()
    val loading by model.loading.collectAsState()
    val writable by model.writable.collectAsState()
    val catalogVersion by client.catalogVersion.collectAsState()
    LaunchedEffect(catalogVersion) { model.load() }

    HorizontalDivider()
    AccountSection(client)
    HorizontalDivider()
    SectionTitle("Models")
    if (loading) CircularProgressIndicator()
    DefaultModelRow(client, model, views["agent-default-model"])
    ProvidersSection(client, model, views)
    HorizontalDivider()
    SectionTitle("Server settings")
    if (!writable) Text("This DSH host does not allow changing settings from clients.", color = MaterialTheme.colorScheme.error)
    views.keys.filter { it != "llm-pi-ai" && it != "agent-default-model" }
        .sortedWith(compareBy({ it.startsWith("ui-") }, { NS_TITLES[it] ?: it }))
        .forEach { ns -> NamespaceCard(model, ns, views[ns]!!) }
}

@Composable
private fun NamespaceCard(model: SettingsModel, ns: String, view: JsonObject, path: List<String> = emptyList(), title: String? = null, initiallyOpen: Boolean = false) {
    var open by remember(ns, path) { mutableStateOf(initiallyOpen) }
    val graph = remember(view["schema"]) { SchemaGraph(view["schema"]) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title ?: NS_TITLES[ns] ?: humanize(ns), fontWeight = FontWeight.Medium)
                    val custom = (view["user"].at(path) as? JsonObject)?.size ?: 0
                    Text(ns + (if (path.isNotEmpty()) " › " + path.joinToString(" › ") else "") + (if (custom > 0) " · $custom customized" else "") +
                        (view["applies"].str?.let { if (it != "live") " · applies on $it" else "" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (open) Column(Modifier.padding(start = 14.dp, end = 8.dp, bottom = 12.dp)) {
                val node = graph.nodeAt(path)
                if (node == null) Text("No editable schema", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SchemaField(graph, node, path, title ?: ns, view["value"].at(path), view["user"].at(path), model.secrets(view), model.actions(ns))
            }
        }
    }
}

@Composable
private fun DefaultModelRow(client: DshClient, model: SettingsModel, view: JsonObject?) {
    var picking by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    LaunchedEffect(Unit) { catalog = client.modelCatalog() }
    val v = view?.get("value")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Default model for new sessions")
            Text(v["provider"].str?.let { "$it / ${v["model"].str}" } ?: catalog?.default?.let { "${it.provider} / ${it.model} (built-in default)" } ?: "—",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton({ picking = true }, enabled = view != null) { Text("Change") }
    }
    if (picking) {
        AlertDialog(onDismissRequest = { picking = false }, title = { Text("Default model") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                catalog?.groups?.forEach { g ->
                    item { Text(g.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                    items(g.models) { m ->
                        Text(m.name, Modifier.fillMaxWidth().clickable {
                            picking = false
                            model.mutate("agent-default-model", listOf(
                                buildJsonObject { put("op", "set"); put("path", buildJsonArray { add(JsonPrimitive("provider")) }); put("value", g.id) },
                                buildJsonObject { put("op", "set"); put("path", buildJsonArray { add(JsonPrimitive("model")) }); put("value", m.id) },
                            ))
                        }.padding(vertical = 8.dp))
                    }
                }
            }
        }, confirmButton = { TextButton({ picking = false }) { Text("Close") } })
    }
}

@Composable
private fun ProvidersSection(client: DshClient, model: SettingsModel, views: Map<String, JsonObject>) {
    var providers by remember { mutableStateOf<List<LlmListConfigurableProvidersResultItem>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<Pair<String, List<LlmDiscoverModelsResultItem>>?>(null) }
    val scope = rememberCoroutineScope()
    val catalogVersion by client.catalogVersion.collectAsState()
    LaunchedEffect(catalogVersion) { providers = client.guard("Load providers") { client.api.llm.listConfigurableProviders() } }
    val list = providers ?: return
    val visible = list.filter { showAll || it.declared != false }
    Text("Providers (${visible.size}${if (!showAll) " configured" else ""})", style = MaterialTheme.typography.labelLarge)
    visible.forEach { p ->
        val view = views[p.settingsNs] ?: return@forEach
        Column {
            NamespaceCard(model, p.settingsNs, view, path = p.settingsPath, title = p.displayName + if (p.declared == false) " (not configured)" else "")
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            if (p.settingsNs == "llm-pi-ai") TextButton({
                scope.launch {
                    client.guard("Discover models") { client.api.llm.discoverModels(p.settingsNs, LlmModelDiscoveryRequest(provider = p.provider)) }?.let { discovered = p.displayName to it }
                }
            }) { Text("Discover models for ${p.displayName}") }
        }
    }
    TextButton({ showAll = !showAll }) { Text(if (showAll) "Show configured providers only" else "Show all ${list.size} provider types") }
    discovered?.let { (name, models) ->
        AlertDialog(onDismissRequest = { discovered = null }, title = { Text("$name models") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (models.isEmpty()) item { Text("No models reported.") }
                items(models) { m ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(m.name ?: m.id, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(m.id, m.contextWindow?.let { "ctx ${formatTokens(it.toLong())}" }, m.inputModalities?.joinToString("/")).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }, confirmButton = { TextButton({ discovered = null }) { Text("Close") } })
    }
}

/** DeepSeek platform account (account API). */
@Composable
private fun AccountSection(client: DshClient) {
    val platform = LocalPlatform.current
    var view by remember { mutableStateOf<JsonElement?>(null) }
    var profile by remember { mutableStateOf<JsonElement?>(null) }
    var balance by remember { mutableStateOf<JsonElement?>(null) }
    val scope = rememberCoroutineScope()
    val meta = remember {
        buildJsonObject { put("version", "dsh-native-1.0"); put("locale", "en"); put("timezoneOffsetSeconds", 0) }
    }
    LaunchedEffect(Unit) {
        view = runCatching { client.conn.callRaw("account/getState", JsonObject(emptyMap())) }.getOrNull()
        runCatching { client.conn.streamRaw("account/watch", JsonObject(emptyMap())).collect { view = it } }
    }
    val status = view["status"].str
    LaunchedEffect(status) {
        if (status == "signed-in") {
            profile = runCatching { client.conn.callRaw("account/getProfile", buildJsonObject { put("client", meta) }) }.getOrNull()
            balance = runCatching { client.conn.callRaw("account/getBalance", buildJsonObject { put("client", meta) }) }.getOrNull()
        }
    }
    val attempt = view["attempt"]
    LaunchedEffect(attempt["authorizeUrl"].str) { attempt["authorizeUrl"].str?.let { platform.openUrl(it) } }

    SectionTitle("DeepSeek account")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AccountCircle, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            when (status) {
                null -> Text("Loading…")
                "signed-in" -> {
                    val p = profile["value"] ?: profile
                    Text(p["name"].str ?: p["contact"].str ?: "Signed in", fontWeight = FontWeight.Medium)
                    val b = (balance["value"] ?: balance).arr?.joinToString { "${it["balance"].str} ${it["currency"].str}" }
                    b?.let { Text("Balance: $it", style = MaterialTheme.typography.bodySmall) }
                }
                else -> Text(if (attempt != null && attempt !is kotlinx.serialization.json.JsonNull) "Signing in (${attempt["phase"].str})…" else "Signed out",
                    style = MaterialTheme.typography.bodyMedium)
            }
            attempt["errorCode"].str?.let { Text("Sign-in failed: $it (DSH may only accept sign-in from the host machine)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val attemptId = attempt["id"].str
        when {
            status == "signed-in" -> OutlinedButton({ scope.launch { client.guard("Sign out") { client.conn.callRaw("account/signOut", buildJsonObject { put("client", meta) }) } } }) { Text("Sign out") }
            attemptId != null -> {
                attempt["authorizeUrl"].str?.let { url -> Button({ platform.openUrl(url) }) { Text("Open sign-in page") } }
                OutlinedButton({ scope.launch { client.guard("Cancel sign-in") { client.conn.callRaw("account/cancelSignIn", buildJsonObject { put("attemptId", attemptId) }) } } }) { Text("Cancel") }
            }
            status != null -> Button({
                scope.launch {
                    val origin = client.conn.environment.baseUrl.substringBefore("://") + "://" + client.conn.environment.baseUrl.substringAfter("://").substringBefore('/')
                    client.guard("Sign in") {
                        client.conn.callRaw("account/startSignIn", buildJsonObject { put("client", meta); put("callbackOrigin", origin); put("loginSource", "desktop") })
                    }?.let { view = it }
                }
            }) { Text("Sign in") }
        }
        view["links"]["usageUrl"].str?.let { u -> TextButton({ platform.openUrl(u) }) { Text("Usage") } }
        view["links"]["topUpUrl"].str?.let { u -> TextButton({ platform.openUrl(u) }) { Text("Top up") } }
    }
}
