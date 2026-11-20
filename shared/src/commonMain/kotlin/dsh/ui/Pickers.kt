package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.gen.AgentPresetRosterPresetsItem
import dsh.gen.ModelCatalog
import dsh.gen.ModelCatalogGroupsItemModelsItem
import dsh.gen.PermissionCatalogOptionsItem
import dsh.model.SessionProjections
import kotlinx.coroutines.launch

@Composable
fun ModelPickerDialog(client: DshClient, sessionId: String, proj: SessionProjections, onDismiss: () -> Unit) {
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    var filter by remember { mutableStateOf("") }
    val current = proj.modelNext
    var chosen by remember { mutableStateOf<Pair<String, ModelCatalogGroupsItemModelsItem>?>(null) }
    var effort by remember { mutableStateOf(proj.reasoningEffort) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        catalog = client.modelCatalog()
        val cur = current
        if (cur != null) catalog?.groups?.firstOrNull { it.id == cur.first }?.models?.firstOrNull { it.id == cur.second }?.let { chosen = cur.first to it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter models") }, modifier = Modifier.fillMaxWidth())
                val c = catalog
                if (c == null) CircularProgressIndicator()
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    for (g in c.groups) {
                        val models = g.models.filter { filter.isBlank() || it.name.contains(filter, true) || it.id.contains(filter, true) || g.name.contains(filter, true) }
                        if (models.isEmpty()) continue
                        item(key = "g:" + g.id) { Text(g.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                        items(models, key = { g.id + "/" + it.id }) { m ->
                            val sel = chosen?.first == g.id && chosen?.second?.id == m.id
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { chosen = g.id to m; effort = m.reasoning?.defaultEffort ?: effort.takeIf { e -> m.reasoning?.efforts?.any { it.id == e } == true } }
                                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                    m.description?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                                }
                                if (current?.first == g.id && current.second == m.id) Text("current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (c.failures.isNotEmpty()) item { Text("${c.failures.size} provider(s) unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
                val efforts = chosen?.second?.reasoning?.efforts.orEmpty()
                if (efforts.isNotEmpty()) {
                    Text("Reasoning effort", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        efforts.forEach { e -> FilterChip(effort == e.id, { effort = e.id }, { Text(e.name) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val ch = chosen ?: return@TextButton
                scope.launch { client.selectModel(sessionId, ch.first, ch.second.id, effort.takeIf { ch.second.reasoning != null }) }
                onDismiss()
            }, enabled = chosen != null) { Text("Use model") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PermissionPickerDialog(client: DshClient, sessionId: String, current: String?, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf<List<PermissionCatalogOptionsItem>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { options = client.permissionCatalog()?.options.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission mode") },
        text = {
            Column {
                val o = options
                if (o == null) CircularProgressIndicator()
                else o.forEach { opt ->
                    Row(Modifier.fillMaxWidth().clickable {
                        scope.launch { client.guard("Set permission") { client.api.commands.execute(sessionId, "/permission ${opt.value}", emptyList()) } }
                        onDismiss()
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current == opt.value, null)
                        Column { Text(opt.name); opt.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
fun PresetPickerDialog(client: DshClient, sessionId: String, current: String?, onDismiss: () -> Unit) {
    var presets by remember { mutableStateOf<List<AgentPresetRosterPresetsItem>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { presets = client.agentPresets()?.presets.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent preset") },
        text = {
            Column {
                val p = presets
                if (p == null) CircularProgressIndicator()
                else p.forEach { preset ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = preset.broken == null) {
                        scope.launch { client.guard("Select preset") { client.api.agentPresets.select(sessionId, preset.id) } }
                        onDismiss()
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current == preset.id, null, enabled = preset.broken == null)
                        Column {
                            Text((preset.name ?: preset.id) + if (preset.isDefault) " (default)" else "")
                            (preset.broken ?: preset.description)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (preset.broken != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
