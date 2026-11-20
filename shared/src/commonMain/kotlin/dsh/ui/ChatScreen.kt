package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.data.ConversationController
import dsh.data.DshClient
import dsh.data.displayTitle
import dsh.gen.CommandsExecuteSubmittedAttachmentsItem
import dsh.gen.CommandsListResultItem
import dsh.gen.SessionFollowRequestAddress
import dsh.model.ChatItem
import dsh.model.SessionProjections
import dsh.model.visible
import dsh.platform.PickedFile
import dsh.transport.get
import dsh.transport.str
import kotlinx.coroutines.delay
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(client: DshClient, nav: MainNav, sessionId: String, wide: Boolean) {
    val conv = remember(sessionId) { client.conversation(sessionId) }
    LaunchedEffect(conv) { conv.start() }
    ChatView(client, nav, conv, sessionId, wide, subagentLabel = null)
}

@Composable
fun SubagentChatScreen(client: DshClient, nav: MainNav, ref: SubagentRef, wide: Boolean) {
    val conv = remember(ref) {
        ConversationController(client, ref.child, SessionFollowRequestAddress.Subagent(ref.parent, ref.child, ref.mode))
    }
    LaunchedEffect(conv) { conv.start() }
    androidx.compose.runtime.DisposableEffect(conv) { onDispose { conv.stop() } }
    ChatView(client, nav, conv, ref.child, wide, subagentLabel = ref.label ?: "Subagent", parentId = ref.parent)
}

@Composable
private fun ChatView(client: DshClient, nav: MainNav, conv: ConversationController, sessionId: String, wide: Boolean, subagentLabel: String?, parentId: String? = null) {
    val state by conv.state.collectAsState()
    val projections by client.projections.collectAsState()
    val sessions by client.sessions.collectAsState()
    val waterfalls by client.waterfalls.collectAsState()
    val proj = projections[sessionId] ?: SessionProjections()
    val running = sessions[sessionId]?.running == true || state.draft != null
    var showAll by remember { mutableStateOf(false) }
    val items = remember(state.items, showAll) { state.items.visible(showAll).asReversed() }
    val listState = rememberLazyListState()
    val mine = waterfalls.filter { it.agentId == sessionId }

    // Page older history when scrolled near the top (reverse layout: last index = oldest).
    val nearTop by remember { derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3 } }
    LaunchedEffect(nearTop, state.hasMore) { if (nearTop && state.hasMore) conv.loadOlder() }

    Column(Modifier.fillMaxSize().imePadding()) {
        ChatHeader(client, nav, sessionId, proj, running, wide, subagentLabel, parentId, showAll) { showAll = !showAll }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.draft?.let { d ->
                        item(key = "draft") { DraftView(d) }
                    }
                    if (running && state.draft == null) item(key = "working") { WorkingIndicator() }
                    items(items, key = { it.key }) { item ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 920.dp).fillMaxWidth()) {
                                ChatItemView(item, conv, client, nav, sessionId)
                            }
                        }
                    }
                    if (state.loadingOlder) item(key = "older") { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } }
                    if (!state.hasMore && items.isEmpty()) item(key = "empty") {
                        Text("Start the conversation below.", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        for (w in mine) InteractionCard(client, w)
        QueueView(conv, proj)
        if (conv.isSubagent && conv.subagentMode != "continuable") {
            Text("One-shot subagent — read only", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Composer(client, conv, sessionId, running, enabled = sessions[sessionId]?.agentAvailable != false || subagentLabel != null)
    }
}

@Composable
private fun ChatHeader(
    client: DshClient, nav: MainNav, sessionId: String, proj: SessionProjections, running: Boolean, wide: Boolean,
    subagentLabel: String?, parentId: String?, showAll: Boolean, toggleAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var permPicker by remember { mutableStateOf(false) }
    var presetPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val home by client.home.collectAsState()
    val cwd = client.sessions.value[sessionId]?.cwd
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!wide || subagentLabel != null) IconButton({ if (subagentLabel != null) nav.subagent = null else nav.selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f).padding(start = if (wide && subagentLabel == null) 12.dp else 0.dp)) {
                Text(
                    (subagentLabel?.let { "$it · " } ?: "") + proj.displayTitle(if (subagentLabel != null) "Subagent" else "New session"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (running) { StatusDot(LocalExtraColors.current.running, pulsing = true, size = 7.dp); Text("Working", style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.running) }
                    Text(shortPath(cwd, home), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (subagentLabel == null) {
                IconButton({ nav.panel = if (nav.panel == SidePanel.Files) null else SidePanel.Files }) { Icon(Icons.Default.FolderOpen, "Files") }
                IconButton({ nav.panel = if (nav.panel == SidePanel.Terminal) null else SidePanel.Terminal }) { Icon(Icons.Default.Terminal, "Terminal") }
                IconButton({ nav.panel = if (nav.panel == SidePanel.Changes) null else SidePanel.Changes }) { Icon(Icons.Default.Difference, "Changes") }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (subagentLabel == null) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
                        DropdownMenuItem({ Text("Fork session") }, { menu = false; scope.launch { client.forkSession(sessionId)?.let { nav.selected = it } } })
                        DropdownMenuItem({ Text("Plan") }, { menu = false; nav.panel = SidePanel.Plan })
                        DropdownMenuItem({ Text("Goal") }, { menu = false; nav.panel = SidePanel.Goal })
                        DropdownMenuItem({ Text("Subagents") }, { menu = false; nav.panel = SidePanel.Subagents })
                        DropdownMenuItem({ Text("Background jobs") }, { menu = false; nav.panel = SidePanel.Jobs })
                        DropdownMenuItem({ Text("Trajectory & stats") }, { menu = false; nav.panel = SidePanel.Trajectory })
                        DropdownMenuItem({ Text("Export session (ZIP)") }, { menu = false; scope.launch { exportSession(client, sessionId, proj, platform) } })
                    }
                    DropdownMenuItem({ Text(if (showAll) "Hide internal events" else "Show all events") }, { menu = false; toggleAll() })
                    DropdownMenuItem({ Text("Copy session id") }, { menu = false; platform.copyToClipboard(sessionId) })
                }
            }
        }
        if (subagentLabel == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(proj.modelNext?.let { "${it.second}" + (proj.reasoningEffort?.let { e -> " · $e" } ?: "") } ?: "Model") { modelPicker = true }
                SmallChip(proj.permission ?: "Permissions") { permPicker = true }
                SmallChip(proj.agentPreset ?: "Preset") { presetPicker = true }
                if (proj.planActive) SmallChip("Plan mode", highlighted = true) { nav.panel = SidePanel.Plan }
                proj.goal?.let { g -> SmallChip("Goal: ${g["phase"].str ?: ""}", highlighted = true) { nav.panel = SidePanel.Goal } }
                val used = proj.contextUsed; val win = proj.contextWindow
                if (used != null && win != null && win > 0) SmallChip("ctx ${formatTokens(used)}/${formatTokens(win)} (${used * 100 / win}%)") { nav.panel = SidePanel.Trajectory }
            }
        }
    }
    if (rename) TextPromptDialog("Rename session", proj.displayTitle(""), onDismiss = { rename = false }) { t -> rename = false; scope.launch { client.renameSession(sessionId, t) } }
    if (modelPicker) ModelPickerDialog(client, sessionId, proj, onDismiss = { modelPicker = false })
    if (permPicker) PermissionPickerDialog(client, sessionId, proj.permission, onDismiss = { permPicker = false })
    if (presetPicker) PresetPickerDialog(client, sessionId, proj.agentPreset, onDismiss = { presetPicker = false })
}

private suspend fun exportSession(client: DshClient, sessionId: String, proj: SessionProjections, platform: dsh.platform.PlatformServices) {
    val bytes = client.guard("Export") {
        client.conn.httpLayer.getBytes("session.export?sessionId=" + sessionId.encodeURLParameter()).first
    } ?: return
    val name = proj.displayTitle("session").replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().replace(' ', '-').ifBlank { "session" } + ".zip"
    platform.saveFile(name, "application/zip", bytes)?.let { client.notice("Exported to $it", isError = false) }
}

@Composable
fun SmallChip(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    AssistChip(onClick, { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        modifier = Modifier.heightIn(max = 30.dp),
        colors = if (highlighted) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors())
}

@Composable
private fun WorkingIndicator() {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueView(conv: ConversationController, proj: SessionProjections) {
    val queued = proj.queueNextTurn.map { it to "next turn" } + proj.queueNextStep.map { it to "next step" }
    val user = queued.filter { it.first["role"].str == "user" && it.first["source"]["kind"].str.let { k -> k == null || k == "user" || k == "client" } }
    if (user.isEmpty()) return
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Queued (${user.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((q, where) in user) {
            val id = q["id"].str ?: continue
            val text = dsh.model.ConversationFolder.textOf(q["content"]).ifBlank { "(attachment)" }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (where == "next turn") IconButton({ scope.launch { conv.queueSteer(id) } }) { Icon(Icons.Default.KeyboardDoubleArrowRight, "Steer now", Modifier.size(18.dp)) }
                    IconButton({ editing = id to text }) { Icon(Icons.Default.Edit, "Edit", Modifier.size(18.dp)) }
                    IconButton({ scope.launch { conv.queueRemove(id) } }) { Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
        }
    }
    editing?.let { (id, text) ->
        TextPromptDialog("Edit queued message", text, multiline = true, onDismiss = { editing = null }) { t -> editing = null; scope.launch { conv.queueEdit(id, t) } }
    }
}

private data class Suggestion(val insert: String, val label: String, val detail: String?)

@Composable
private fun Composer(client: DshClient, conv: ConversationController, sessionId: String, running: Boolean, enabled: Boolean) {
    var value by remember(sessionId) { mutableStateOf(TextFieldValue("")) }
    val attachments = remember(sessionId) { mutableStateListOf<PickedFile>() }
    var steer by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<List<CommandsListResultItem>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val catalogVersion by client.catalogVersion.collectAsState()
    LaunchedEffect(sessionId, catalogVersion) { commands = client.commands(sessionId).orEmpty() }
    val shared by SharedInbox.pending.collectAsState()
    LaunchedEffect(shared) {
        shared?.let { sh ->
            sh.text?.let { t -> value = TextFieldValue(value.text + (if (value.text.isEmpty()) "" else "\n") + t) }
            attachments += sh.files
            SharedInbox.pending.value = null
        }
    }

    // Trigger detection: "/cmd" at start, "@query" token at cursor.
    LaunchedEffect(value.text, value.selection) {
        val text = value.text
        val cursor = value.selection.start.coerceIn(0, text.length)
        val before = text.substring(0, cursor)
        val at = Regex("(?:^|\\s)@([^\\s@]*)$").find(before)
        suggestions = when {
            text.startsWith("/") && !text.contains(' ') && !text.contains('\n') -> {
                val q = text.removePrefix("/")
                commands.filter { it.name.startsWith(q, true) }.take(12).map { Suggestion("/${it.name} ", "/${it.name}", it.description) }
            }
            at != null -> {
                val q = at.groupValues[1]
                delay(150)
                val files = runCatching { client.api.fileReferences.list(sessionId, q) }.getOrNull().orEmpty().take(10)
                    .map { Suggestion("@${it.path} ", "@${it.path}", it.kind) }
                val sess = if (q.length >= 1) runCatching { client.api.sessionReferenceResolver.candidates(sessionId, q) }.getOrNull().orEmpty().take(4)
                    .map { Suggestion("${it.mention} ", it.mention, it.displayTitle ?: it.label) } else emptyList()
                files + sess
            }
            else -> emptyList()
        }
    }

    fun applySuggestion(s: Suggestion) {
        val text = value.text
        val cursor = value.selection.start.coerceIn(0, text.length)
        val newText: String; val newCursor: Int
        if (text.startsWith("/") && !text.contains(' ')) {
            newText = s.insert; newCursor = s.insert.length
        } else {
            val before = text.substring(0, cursor)
            val start = before.lastIndexOf('@').coerceAtLeast(0)
            newText = text.substring(0, start) + s.insert + text.substring(cursor)
            newCursor = start + s.insert.length
        }
        value = TextFieldValue(newText, TextRange(newCursor))
        suggestions = emptyList()
    }

    fun send() {
        val text = value.text.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || sending) return
        sending = true
        val files = attachments.toList()
        scope.launch {
            val ok = if (text.startsWith("/") && commands.any { text.removePrefix("/").substringBefore(' ') == it.name }) {
                val atts = files.filter { it.mediaType in ConversationController.IMAGE_TYPES }.map {
                    CommandsExecuteSubmittedAttachmentsItem.Image(it.mediaType, kotlin.io.encoding.Base64.encode(it.bytes), it.name)
                }
                val r = client.guard("Command") { client.api.commands.execute(sessionId, text, atts) }
                val kind = r["result"]["kind"].str
                if (r == null || r is kotlinx.serialization.json.JsonNull) { client.notice("Unknown or malformed command: $text"); false }
                else { if (kind == "error") client.notice(r["result"]["text"].str ?: "Command failed"); kind != "error" }
            } else conv.prompt(text, files, if (steer && running) "steer" else "queue")
            if (ok) { value = TextFieldValue(""); attachments.clear(); steer = false }
            sending = false
        }
    }

    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (suggestions.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(suggestions) { s ->
                            Row(Modifier.fillMaxWidth().clickable { applySuggestion(s) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(s.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                s.detail?.let { Text("  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEachIndexed { i, f ->
                        InputChip(true, { attachments.removeAt(i) }, { Text(f.name, maxLines = 1) },
                            leadingIcon = { Icon(if (f.mediaType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton({ attachMenu = true }, enabled = enabled) { Icon(Icons.Default.AttachFile, "Attach") }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text("Image") }, { attachMenu = false; scope.launch { attachments += platform.pickFiles(imagesOnly = true) } }, leadingIcon = { Icon(Icons.Default.Image, null) })
                        DropdownMenuItem({ Text("File") }, { attachMenu = false; scope.launch { attachments += platform.pickFiles(imagesOnly = false) } }, leadingIcon = { Icon(Icons.Default.AttachFile, null) })
                    }
                }
                TextField(
                    value, { value = it }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 220.dp).onPreviewKeyEvent { e ->
                        if (!platform.isMobile && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                            if (suggestions.isNotEmpty()) applySuggestion(suggestions.first()) else send(); true
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && running) {
                            scope.launch { conv.cancel() }; true
                        } else false
                    },
                    placeholder = { Text(if (running) "Queue a message, or toggle Steer to redirect…" else "Message the agent — / for commands, @ for files") },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    maxLines = 10,
                )
                if (platform.canDictate) IconButton({
                    scope.launch { platform.dictate()?.let { t -> value = TextFieldValue((value.text + (if (value.text.isBlank()) "" else " ") + t), TextRange(value.text.length + t.length + 1)) } }
                }, enabled = enabled) { Icon(Icons.Default.Mic, "Dictate") }
                Spacer(Modifier.width(6.dp))
                if (running) {
                    FilledIconButton({ scope.launch { conv.cancel() } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                }
                FilledIconButton(::send, enabled = enabled && !sending && (value.text.isNotBlank() || attachments.isNotEmpty())) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            if (running) {
                Row(Modifier.padding(start = 48.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(steer, { steer = !steer }, { Text("Steer current turn", style = MaterialTheme.typography.labelSmall) })
                    Text(if (steer) "  Message is injected into the running turn" else "  Message waits for the next turn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
