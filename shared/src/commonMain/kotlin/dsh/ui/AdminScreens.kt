package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import dsh.data.displayTitle
import dsh.transport.DshException
import dsh.transport.HttpStatusException
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.num
import dsh.transport.obj
import dsh.transport.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ------------------------------------------------------------------------------------------
// Scheduled tasks (schedule API)
// ------------------------------------------------------------------------------------------

@Composable
fun SchedulesScreen(client: DshClient, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<JsonElement>?>(null) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var history by remember { mutableStateOf<JsonElement?>(null) }
    val projections by client.projections.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        try {
            items = client.conn.callRaw("schedule/catalog", JsonObject(emptyMap())).arr.orEmpty(); unavailable = null
        } catch (e: HttpStatusException) {
            unavailable = if (e.status == 404) "Scheduled tasks are not enabled on this DSH server." else e.message
        } catch (e: DshException) { unavailable = e.message }
    }
    LaunchedEffect(Unit) { client.events.collect { if (it.event == "schedule/changed") reload++ } }

    PanelScaffold("Scheduled tasks", onBack, docked = false, actions = { IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") } }) {
        val list = items
        when {
            unavailable != null -> Placeholder(unavailable!!)
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Placeholder("No scheduled tasks. Ask an agent to schedule something (e.g. “every morning, summarize the repo changes”).")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it["id"].str + it["sessionId"].str }) { s ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s["title"].str ?: "Task", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(s["status"].str ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(describeTiming(s), style = MaterialTheme.typography.bodySmall)
                            Text(s["prompt"].str ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("Session: " + projections[s["sessionId"].str].displayTitle(s["sessionId"].str ?: ""), style = MaterialTheme.typography.labelSmall)
                            s["lastDelivery"]?.takeIf { it is JsonObject }?.let { d -> Text("Last run: ${d["status"].str ?: d["outcome"].str ?: ""} ${d["at"].str ?: d["deliveredAt"].str ?: ""}", style = MaterialTheme.typography.labelSmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton({ editing = s }) { Text("Edit") }
                                TextButton({
                                    scope.launch {
                                        history = client.guard("History") {
                                            client.conn.callRaw("schedule/history", buildJsonObject { put("request", buildJsonObject { put("limit", 20); put("id", s["id"].str ?: ""); put("sessionId", s["sessionId"].str ?: "") }) })
                                        }
                                    }
                                }) { Text("History") }
                                TextButton({
                                    scope.launch {
                                        client.guard("Delete schedule") { client.conn.callRaw("schedule/delete", buildJsonObject { put("request", buildJsonObject { put("id", s["id"].str ?: ""); put("sessionId", s["sessionId"].str ?: "") }) }) }
                                        reload++
                                    }
                                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { s -> ScheduleEditDialog(client, s, onDismiss = { editing = null }) { editing = null; reload++ } }
    history?.let { h ->
        AlertDialog(onDismissRequest = { history = null }, title = { Text("Delivery history") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                val recs = h["records"].arr.orEmpty()
                if (recs.isEmpty()) item { Text(h["code"].str ?: "No deliveries yet.") }
                items(recs) { r -> Text(r.toString().take(240), style = MonoStyle, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }, confirmButton = { TextButton({ history = null }) { Text("Close") } })
    }
}

private fun describeTiming(s: JsonElement): String = when (s["kind"].str) {
    "after" -> "Once, ${s["afterSeconds"].long?.let { "$it s after creation" } ?: ""} · next ${s["scheduledAt"].str ?: ""}"
    "at" -> "Once at ${s["scheduledAt"].str}"
    "every" -> "Every ${s["everySeconds"].long?.let { if (it % 3600 == 0L) "${it / 3600} h" else if (it % 60 == 0L) "${it / 60} min" else "$it s" }} · next ${s["scheduledAt"].str ?: ""}"
    "daily" -> "Daily · next ${s["scheduledAt"].str ?: ""}"
    "weekly" -> "Weekly · next ${s["scheduledAt"].str ?: ""}"
    "cron" -> "Cron ${s["cron"]["expression"].str ?: s["cron"].str ?: ""} · next ${s["scheduledAt"].str ?: ""}"
    else -> "${s["kind"].str} · next ${s["scheduledAt"].str ?: ""}"
}

@Composable
private fun ScheduleEditDialog(client: DshClient, s: JsonElement, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var title by remember { mutableStateOf(s["title"].str ?: "") }
    var prompt by remember { mutableStateOf(s["prompt"].str ?: "") }
    var everyMin by remember { mutableStateOf(s["everySeconds"].long?.let { (it / 60).toString() } ?: "") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit scheduled task") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (s["kind"].str == "every") OutlinedTextField(everyMin, { everyMin = it.filter(Char::isDigit) }, label = { Text("Every (minutes)") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton({
            busy = true
            scope.launch {
                val sessionId = s["sessionId"].str ?: ""
                // `expected` must be the exact current list item for this session.
                val expected = runCatching {
                    client.conn.callRaw("schedule/list", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
                        .arr.orEmpty().firstOrNull { it["id"] == s["id"] }
                }.getOrNull()
                if (expected == null) { client.notice("Schedule changed or no longer exists"); busy = false; onSaved(); return@launch }
                val ok = client.guard("Update schedule") {
                    client.conn.callRaw("schedule/update", buildJsonObject {
                        put("request", buildJsonObject {
                            put("expected", expected); put("id", s["id"].str ?: ""); put("sessionId", sessionId)
                            if (title != s["title"].str) put("title", title)
                            if (prompt != s["prompt"].str) put("prompt", prompt)
                            val newEvery = everyMin.toLongOrNull()?.times(60)
                            if (s["kind"].str == "every" && newEvery != null && newEvery != s["everySeconds"].long) {
                                put("change", buildJsonObject { put("kind", "every"); put("every_seconds", newEvery) })
                            }
                        })
                    })
                }
                busy = false
                if (ok != null) onSaved()
            }
        }, enabled = !busy && title.isNotBlank() && prompt.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------
// Plugins (pluginManager API)
// ------------------------------------------------------------------------------------------

@Composable
fun PluginsScreen(client: DshClient, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var bundles by remember { mutableStateOf<List<JsonElement>?>(null) }
    var plugins by remember { mutableStateOf<List<JsonElement>?>(null) }
    var registries by remember { mutableStateOf<JsonElement?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        bundles = client.guard("Load bundles") { client.conn.callRaw("pluginManager/listBundles", JsonObject(emptyMap())).arr.orEmpty() } ?: emptyList()
        plugins = client.guard("Load plugins") { client.conn.callRaw("pluginManager/listPlugins", JsonObject(emptyMap())).arr.orEmpty() } ?: emptyList()
        registries = runCatching { client.conn.callRaw("pluginManager/registries", JsonObject(emptyMap())) }.getOrNull()
    }
    LaunchedEffect(Unit) {
        client.events.collect { e ->
            when (e.event) {
                "plugin-manager/changed" -> reload++
                "plugin-manager/install-log" -> { log += e.args.joinToString(" ") { it.str ?: it.toString() }; while (log.size > 200) log.removeAt(0) }
                "plugin-manager/install-state" -> log += "state: " + e.args.joinToString(" ") { it.toString() }.take(200)
            }
        }
    }
    fun change(what: String, method: String, args: JsonObject) {
        scope.launch {
            val r = client.guard(what) { client.conn.callRaw("pluginManager/$method", args) }
            r?.get("error")?.takeIf { it is JsonObject }?.let { client.notice("$what: ${it["message"].str ?: it}") }
            r?.get("warnings")?.arr?.forEach { w -> client.notice(w.str ?: "", isError = false) }
            reload++
        }
    }

    PanelScaffold("Plugins", onBack, docked = false, actions = {
        IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(tab == 0, { tab = 0 }, { Text("Bundles") })
                FilterChip(tab == 1, { tab = 1 }, { Text("Plugins") })
                FilterChip(tab == 2, { tab = 2 }, { Text("Install") })
            }
            when (tab) {
                0 -> ItemList(bundles) { b ->
                    PluginRow(
                        title = b["meta"]["title"].str ?: b["name"].str ?: "?",
                        subtitle = listOfNotNull(b["version"].str?.let { "v$it" }, b["description"].str).joinToString(" · "),
                        enabled = b["enabled"].bool == true,
                        readOnly = b["readOnlyReason"].reason(),
                        onToggle = { on -> change("Toggle bundle", "setBundleEnabled", buildJsonObject { put("name", b["name"].str ?: ""); put("enabled", on) }) },
                        onRemove = if (b["removable"].bool == true) ({ change("Remove bundle", "removeBundle", buildJsonObject { put("name", b["name"].str ?: "") }) }) else null,
                    )
                }
                1 -> ItemList(plugins) { p ->
                    val id = p["entryId"].str ?: return@ItemList
                    PluginRow(
                        title = p["meta"]["title"].str ?: p["moduleName"].str ?: id,
                        subtitle = listOfNotNull(p["moduleName"].str, p["fiberPhase"].str).joinToString(" · "),
                        enabled = p["enabled"].bool == true,
                        readOnly = p["readOnlyReason"].reason(),
                        onToggle = { on -> change("Toggle plugin", "setPluginEnabled", buildJsonObject { put("id", id); put("enabled", on) }) },
                        onRemove = null,
                    )
                }
                else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var spec by remember { mutableStateOf("") }
                    var inspection by remember { mutableStateOf<JsonElement?>(null) }
                    Text("Install a plugin bundle from the npm registry (e.g. a package name or name@version).", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(spec, { spec = it; inspection = null }, singleLine = true, label = { Text("Package spec") }, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            scope.launch { inspection = client.guard("Inspect") { client.conn.callRaw("pluginManager/inspect", buildJsonObject { put("spec", spec.trim()); put("options", JsonObject(emptyMap())) }) } }
                        }, enabled = spec.isNotBlank() && !installing) { Text("Inspect") }
                        Button({
                            installing = true; log.clear()
                            scope.launch {
                                val r = client.guard("Install") { client.conn.callRaw("pluginManager/installBundle", buildJsonObject { put("spec", spec.trim()); put("options", JsonObject(emptyMap())) }) }
                                installing = false
                                r?.let { log += "result: stage=${it["stage"].str} changed=${it["changed"].bool} ${it["error"]["message"].str ?: ""}" }
                                reload++
                            }
                        }, enabled = spec.isNotBlank() && !installing && inspection?.get("status")?.str != "refused") { Text(if (installing) "Installing…" else "Install") }
                    }
                    inspection?.let { i ->
                        if (i["status"].str == "refused") Text("Refused: ${i["reason"].str} (${i["problem"].str})", color = MaterialTheme.colorScheme.error)
                        else Text("${i["name"].str ?: spec} ${i["version"].str ?: ""} — ${i["description"].str ?: ""}${if (i["bundle"].bool == true) " (bundle)" else ""}")
                    }
                    registries?.let { r -> Text("Registry: ${r["resolved"].str ?: r["registry"].str ?: "default"}", style = MaterialTheme.typography.labelSmall) }
                    if (log.isNotEmpty()) CodeBlock(log.joinToString("\n"), Modifier.fillMaxWidth().heightIn(max = 320.dp))
                }
            }
        }
    }
}

@Composable
private fun ItemList(items: List<JsonElement>?, row: @Composable (JsonElement) -> Unit) {
    if (items == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items) { row(it) }
    }
}

@Composable
private fun PluginRow(title: String, subtitle: String, enabled: Boolean, readOnly: String?, onToggle: (Boolean) -> Unit, onRemove: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                readOnly?.let { Text("Read-only: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            onRemove?.let { TextButton(it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
            Switch(enabled, onToggle, enabled = readOnly == null)
        }
    }
}

private fun JsonElement?.reason(): String? = this?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { it.str ?: it["code"].str ?: it.toString() }
