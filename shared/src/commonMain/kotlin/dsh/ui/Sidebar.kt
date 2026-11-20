package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.data.SessionSummary
import dsh.data.WorkspaceInfo
import dsh.data.displayTitle
import dsh.transport.ConnState
import kotlinx.coroutines.launch

private sealed interface Row_ {
    data class Header(val key: String, val title: String, val subtitle: String?, val workspace: WorkspaceInfo?, val count: Int) : Row_
    data class Session(val s: SessionSummary, val group: String) : Row_
}

@Composable
fun Sidebar(app: AppState, client: DshClient, nav: MainNav, compact: Boolean) {
    val sessions by client.sessions.collectAsState()
    val projections by client.projections.collectAsState()
    val workspaces by client.workspaces.collectAsState()
    val archived by client.archived.collectAsState()
    val pinned by client.pinned.collectAsState()
    val waterfalls by client.waterfalls.collectAsState()
    val home by client.home.collectAsState()
    val conn by client.state.collectAsState()
    val env by app.activeEnv.collectAsState()
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var menu by remember { mutableStateOf(false) }
    var newSessionFor by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var showNewSession by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pendingBySession = waterfalls.groupingBy { it.agentId }.eachCount()

    val visible = sessions.values.filter { s ->
        !s.isSubagent && (showArchived || s.id !in archived) && (!s.blank || s.id == nav.selected) &&
            (query.isBlank() || projections[s.id].displayTitle("").contains(query, true) || (s.cwd ?: "").contains(query, true))
    }
    val rows = buildList {
        val pinnedSessions = pinned.mapNotNull { id -> visible.firstOrNull { it.id == id } }
        if (pinnedSessions.isNotEmpty()) {
            add(Row_.Header("pinned", "Pinned", null, null, pinnedSessions.size))
            if (collapsed["pinned"] != true) pinnedSessions.forEach { add(Row_.Session(it, "pinned")) }
        }
        val inWs = HashSet<String>()
        for (w in workspaces) {
            val list = w.sessionIds.mapNotNull { id -> visible.firstOrNull { it.id == id } }.sortedByDescending { it.updatedAt }
            inWs += w.sessionIds
            add(Row_.Header(w.id, w.title, shortPath(w.path, home), w, list.size))
            if (collapsed[w.id] != true) list.forEach { add(Row_.Session(it, w.id)) }
        }
        val other = visible.filter { it.id !in inWs }.sortedByDescending { it.updatedAt }
        if (other.isNotEmpty()) {
            add(Row_.Header("other", "Other sessions", null, null, other.size))
            if (collapsed["other"] != true) other.forEach { add(Row_.Session(it, "other")) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (conn) { is ConnState.Connected -> MaterialTheme.colorScheme.tertiary; ConnState.AuthRequired -> MaterialTheme.colorScheme.error; else -> LocalExtraColors.current.warn },
                pulsing = conn !is ConnState.Connected,
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(env?.name ?: "DSH", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(env?.baseUrl ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Settings") }, { menu = false; nav.showSettings = true; nav.showSchedules = false; nav.showPlugins = false }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                    DropdownMenuItem({ Text("Scheduled tasks") }, { menu = false; nav.showSchedules = true; nav.showSettings = false; nav.showPlugins = false }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
                    DropdownMenuItem({ Text("Plugins") }, { menu = false; nav.showPlugins = true; nav.showSettings = false; nav.showSchedules = false }, leadingIcon = { Icon(Icons.Default.Extension, null) })
                    DropdownMenuItem({ Text(if (showArchived) "Hide archived" else "Show archived") }, { menu = false; showArchived = !showArchived }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                    DropdownMenuItem({ Text("Add workspace folder") }, { menu = false; newSessionFor = null; showNewSession = true }, leadingIcon = { Icon(Icons.Default.Folder, null) })
                    DropdownMenuItem({ Text("Refresh") }, { menu = false; scope.launch { client.refreshSessions() }; client.conn.reconnectNow() }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                    DropdownMenuItem({ Text("Switch environment") }, { menu = false; app.disconnect() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, modifier = Modifier.weight(1f).height(52.dp),
                placeholder = { Text("Search sessions") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface),
            )
            FilledTonalButton({ newSessionFor = workspaces.firstOrNull { w -> w.sessionIds.contains(nav.selected) }; showNewSession = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("New")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { r -> when (r) { is Row_.Header -> "h:" + r.key; is Row_.Session -> "s:" + r.group + ":" + r.s.id } }) { r ->
                when (r) {
                    is Row_.Header -> WorkspaceHeader(r, collapsed[r.key] == true,
                        onToggle = { collapsed[r.key] = collapsed[r.key] != true },
                        onNewSession = { newSessionFor = r.workspace; showNewSession = true },
                        client = client)
                    is Row_.Session -> SessionRow(
                        client, r.s, projections[r.s.id].displayTitle(), selected = nav.selected == r.s.id,
                        pinned = r.s.id in pinned, archived = r.s.id in archived, pending = pendingBySession[r.s.id] ?: 0,
                        onClick = { nav.selected = r.s.id; nav.subagent = null; nav.showSettings = false; nav.showSchedules = false; nav.showPlugins = false },
                        onOpenNew = { id -> nav.selected = id },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showNewSession) {
        NewSessionDialog(client, initialWorkspace = newSessionFor, onDismiss = { showNewSession = false }) { id ->
            showNewSession = false
            if (id != null) { nav.selected = id; nav.subagent = null; nav.showSettings = false }
        }
    }
}

@Composable
private fun WorkspaceHeader(r: Row_.Header, collapsed: Boolean, onToggle: () -> Unit, onNewSession: () -> Unit, client: DshClient) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (r.key == "pinned") Icons.Default.PushPin else Icons.Default.Folder, null, Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(r.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("${r.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.workspace != null) {
            IconButton(onNewSession, Modifier.size(32.dp)) { Icon(Icons.Default.Add, "New session here", Modifier.size(18.dp)) }
            Box {
                IconButton({ menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Workspace menu", Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename workspace") }, { menu = false; rename = true })
                    DropdownMenuItem({ Text("Remove workspace") }, { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (rename && r.workspace != null) {
        TextPromptDialog("Rename workspace", r.title, onDismiss = { rename = false }) { t -> rename = false; scope.launch { client.renameWorkspace(r.workspace.id, t) } }
    }
    if (confirmDelete && r.workspace != null) {
        ConfirmDialog("Remove workspace “${r.title}”?", "Sessions stay on the server; the folder is not deleted.", "Remove",
            onDismiss = { confirmDelete = false }) { confirmDelete = false; scope.launch { client.deleteWorkspace(r.workspace.id) } }
    }
}

@Composable
private fun SessionRow(
    client: DshClient, s: SessionSummary, title: String, selected: Boolean, pinned: Boolean, archived: Boolean, pending: Int,
    onClick: () -> Unit, onOpenNew: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(onClick = onClick).padding(start = 28.dp, end = 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.running) { StatusDot(LocalExtraColors.current.running, pulsing = true, size = 6.dp); Text("running", style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.running) }
                if (pending > 0) Text("● needs input", style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.warn)
                if (archived) Text("archived", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton({ menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Session menu", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
                DropdownMenuItem({ Text(if (pinned) "Unpin" else "Pin") }, { menu = false; scope.launch { if (pinned) client.unpin(s.id) else client.pin(s.id) } })
                DropdownMenuItem({ Text("Fork") }, { menu = false; scope.launch { client.forkSession(s.id)?.let(onOpenNew) } })
                DropdownMenuItem({ Text(if (archived) "Unarchive" else "Archive") }, { menu = false; scope.launch { if (archived) client.unarchive(s.id) else client.archive(s.id) } })
            }
        }
    }
    if (rename) TextPromptDialog("Rename session", title, onDismiss = { rename = false }) { t -> rename = false; scope.launch { client.renameSession(s.id, t) } }
}
