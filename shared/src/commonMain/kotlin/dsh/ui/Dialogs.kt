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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.data.WorkspaceInfo
import dsh.gen.AgentPresetRosterPresetsItem
import dsh.gen.DirectoryListing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun TextPromptDialog(title: String, initial: String, label: String = "", confirm: String = "Save", multiline: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = !multiline, minLines = if (multiline) 4 else 1,
                label = if (label.isNotEmpty()) ({ Text(label) }) else null, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun NewSessionDialog(client: DshClient, initialWorkspace: WorkspaceInfo?, onDismiss: () -> Unit, onCreated: (String?) -> Unit) {
    val workspaces by client.workspaces.collectAsState()
    var selectedWs by remember { mutableStateOf(initialWorkspace?.id ?: workspaces.firstOrNull()?.id) }
    var customDir by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<AgentPresetRosterPresetsItem>>(emptyList()) }
    var preset by remember { mutableStateOf<String?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { presets = client.agentPresets()?.presets.orEmpty().filter { it.broken == null } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Workspace", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(workspaces, key = { it.id }) { w ->
                        Row(Modifier.fillMaxWidth().clickable { selectedWs = w.id; customDir = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedWs == w.id && customDir == null, { selectedWs = w.id; customDir = null })
                            Column { Text(w.title); Text(shortPath(w.path, client.home.value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(customDir != null, { picking = true })
                            Text(customDir?.let { "Folder: " + shortPath(it, client.home.value) } ?: "Choose another folder…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (presets.size > 1) {
                    Text("Agent preset", style = MaterialTheme.typography.labelLarge)
                    Row {
                        OutlinedButton({ presetMenu = true }) { Text(presets.firstOrNull { it.id == preset }?.let { it.name ?: it.id } ?: "Default") }
                        DropdownMenu(presetMenu, { presetMenu = false }) {
                            DropdownMenuItem({ Text("Default") }, { preset = null; presetMenu = false })
                            presets.forEach { p -> DropdownMenuItem({ Column { Text(p.name ?: p.id); p.description?.let { d -> Text(d, style = MaterialTheme.typography.labelSmall, maxLines = 2) } } }, { preset = p.id; presetMenu = false }) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    val id = if (customDir != null) client.createSession(cwd = customDir, agentPreset = preset)
                    else client.createSession(workspaceId = selectedWs, agentPreset = preset)
                    busy = false
                    if (id != null) onCreated(id)
                }
            }, enabled = !busy && (selectedWs != null || customDir != null)) { if (busy) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Create") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
    if (picking) DirectoryPickerDialog(client, onDismiss = { picking = false }) { path -> picking = false; customDir = path }
}

/** Browses directories on the DSH host (`directoryPicker/list`, `directoryPicker/createDirectory`). */
@Composable
fun DirectoryPickerDialog(client: DshClient, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, reload) {
        error = null
        listing = runCatching { client.api.directoryPicker.list(path) }
            .onFailure { error = it.message }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parent = listing?.crumbs?.dropLast(1)?.lastOrNull()
                IconButton({ parent?.let { path = it.path } }, enabled = parent != null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                Text(listing?.path?.let { shortPath(it, listing!!.home) } ?: "Choose folder", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton({ newFolder = true }, enabled = listing != null) { Icon(Icons.Default.CreateNewFolder, "New folder") }
            }
        },
        text = {
            Column {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val l = listing
                if (l == null && error == null) CircularProgressIndicator()
                if (l != null) {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(l.entries.filter { !it.hidden }, key = { it.path }) { e ->
                            Row(Modifier.fillMaxWidth().clickable { path = e.path }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(e.name, Modifier.padding(start = 10.dp))
                            }
                            HorizontalDivider()
                        }
                    }
                    if (l.truncated) Text("Listing truncated", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton({ listing?.path?.let(onPick) }, enabled = listing != null) { Text("Use this folder") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
    if (newFolder) TextPromptDialog("New folder", "", label = "Name", confirm = "Create", onDismiss = { newFolder = false }) { name ->
        newFolder = false
        val base = listing?.path ?: return@TextPromptDialog
        scope.launch { client.guard("Create folder") { client.api.directoryPicker.createDirectory(base, name) }?.let { path = it }; reload++ }
    }
}
