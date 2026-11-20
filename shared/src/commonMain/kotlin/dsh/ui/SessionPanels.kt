package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
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
import dsh.gen.CreateGoalRequest
import dsh.gen.EditGoalRequest
import dsh.gen.GoalRef
import dsh.gen.JobFollowFrame
import dsh.gen.JobFollowFrameOpenedJob
import dsh.gen.JobFollowRequest
import dsh.gen.JobKillRequest
import dsh.gen.JobListRequest
import dsh.model.ChatItem
import dsh.model.SessionProjections
import dsh.transport.DshJson
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.num
import dsh.transport.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------------------------------------------
// Changes: per-turn changed files (GET api/changes.summary / changes.diff)
// ------------------------------------------------------------------------------------------

private data class ChangedFile(val index: Int, val path: String, val display: String, val added: Long, val deleted: Long, val binary: Boolean, val oversized: Boolean)
private data class TurnChanges(val seq: Long, val turn: Int, val files: List<ChangedFile>, val added: Long, val deleted: Long)

@Composable
fun ChangesPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    val conv = remember(sessionId) { client.conversation(sessionId) }
    val state by conv.state.collectAsState()
    val changeEvents = remember(state.items) { state.items.filterIsInstance<ChatItem.Raw>().filter { it.type == "workspace/changes" }.sortedByDescending { it.seq } }
    val summaries = remember(sessionId) { mutableStateMapOf<Long, TurnChanges?>() }
    var openDiff by remember { mutableStateOf<Pair<Long, ChangedFile>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(changeEvents.map { it.seq }, reload) {
        for (e in changeEvents) if (!summaries.containsKey(e.seq) || reload > 0) {
            val text = runCatching { client.conn.httpLayer.getText("changes.summary?sessionId=${sessionId.encodeURLParameter()}&seq=${e.seq}") }.getOrNull()
            summaries[e.seq] = text?.let { parseSummary(e.seq, it) }
        }
    }

    val d = openDiff
    if (d != null) { DiffScreen(client, sessionId, d.first, d.second, docked, onBack = { openDiff = null }, onClose = onClose); return }

    PanelScaffold("Changes", onClose, docked, actions = { IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") } }) {
        val deliverables = state.items.filterIsInstance<ChatItem.Deliverables>()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (deliverables.isNotEmpty()) item {
                Text("Delivered files", style = MaterialTheme.typography.titleSmall)
                deliverables.flatMap { it.files }.distinctBy { it.first }.forEach { (p, desc) ->
                    Row(Modifier.fillMaxWidth().clickable { nav.previewPath = p; nav.panel = SidePanel.Files }.padding(vertical = 4.dp)) {
                        Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                        Column(Modifier.padding(start = 6.dp)) { Text(p, style = MonoStyle); desc?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
            if (changeEvents.isEmpty()) item { Text("No file changes recorded in this session yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(changeEvents, key = { it.seq }) { e ->
                val s = summaries[e.seq]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val turn = e.data["turn"].num?.toInt()
                        when {
                            !summaries.containsKey(e.seq) -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("  Turn $turn…") }
                            s == null -> Text("Turn $turn — changes no longer available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Turn ${s.turn}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("+${s.added}", color = MaterialTheme.colorScheme.tertiary, style = MonoStyle)
                                    Text("  −${s.deleted}", color = MaterialTheme.colorScheme.error, style = MonoStyle)
                                }
                                s.files.forEach { f ->
                                    Row(Modifier.fillMaxWidth().clickable { openDiff = e.seq to f }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(f.display, Modifier.weight(1f), style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (f.binary) Text("binary ", style = MaterialTheme.typography.labelSmall)
                                        Text("+${f.added}", color = MaterialTheme.colorScheme.tertiary, style = MonoStyle)
                                        Text(" −${f.deleted}", color = MaterialTheme.colorScheme.error, style = MonoStyle)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseSummary(seq: Long, text: String): TurnChanges? = runCatching {
    val j = DshJson.parseToJsonElement(text)
    val v = j["value"] ?: j
    TurnChanges(seq, v["turn"].num?.toInt() ?: 0, v["files"].arr.orEmpty().mapIndexed { i, f ->
        ChangedFile(i, f["path"].str ?: "", f["display"].str ?: f["path"].str ?: "", f["added"].long ?: 0, f["deleted"].long ?: 0, f["binary"].bool == true, f["oversized"].bool == true)
    }, v["added"].long ?: 0, v["deleted"].long ?: 0)
}.getOrNull()

@Composable
private fun DiffScreen(client: DshClient, sessionId: String, seq: Long, file: ChangedFile, docked: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    var diff by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seq, file.index) {
        runCatching { client.conn.httpLayer.getText("changes.diff?sessionId=${sessionId.encodeURLParameter()}&seq=$seq&index=${file.index}") }
            .onSuccess { t -> diff = DshJson.parseToJsonElement(t).let { it["value"] ?: it } }
            .onFailure { error = it.message }
    }
    PanelScaffold(file.display.substringAfterLast('/'), onClose, docked, actions = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text(file.display, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val d = diff
            when {
                d == null && error == null -> CircularProgressIndicator()
                d == null -> Unit
                d["kind"].str == "binary" -> Placeholder("Binary file changed")
                d["kind"].str == "oversized" -> Placeholder("File too large to diff")
                else -> {
                    if (d["before"].bool == false) Text("New file", color = MaterialTheme.colorScheme.tertiary)
                    if (d["after"].bool == false) Text("Deleted file", color = MaterialTheme.colorScheme.error)
                    d["hunks"].arr.orEmpty().forEach { h ->
                        Text("@@ -${h["oldStart"].long},${h["oldLines"].long} +${h["newStart"].long},${h["newLines"].long} @@", style = MonoStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        UnifiedDiff(h["lines"].arr.orEmpty().map { it.str ?: "" }, maxLines = 2000)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Plan
// ------------------------------------------------------------------------------------------

@Composable
fun PlanPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    val conv = remember(sessionId) { client.conversation(sessionId) }
    val state by conv.state.collectAsState()
    val projections by client.projections.collectAsState()
    val proj = projections[sessionId] ?: SessionProjections()
    val scope = rememberCoroutineScope()
    val plans = remember(state.items) {
        state.items.filterIsInstance<ChatItem.Tool>().filter { it.name == "exit_plan_mode" }
            .mapNotNull { t -> t.args?.get("plan").str?.let { t to it } }.asReversed()
    }
    var selected by remember { mutableIntStateOf(0) }
    PanelScaffold("Plan", onClose, docked) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Plan mode", fontWeight = FontWeight.SemiBold)
                    Text(if (proj.planActive) "The agent plans before making changes" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(proj.planActive, { on -> scope.launch { client.guard("Plan mode") { client.api.commands.execute(sessionId, if (on) "/plan" else "/plan off", emptyList()) } } })
            }
            HorizontalDivider()
            if (plans.isEmpty()) Placeholder("No plan submitted yet. Turn on plan mode and describe the task.")
            else {
                if (plans.size > 1) Row(Modifier.padding(horizontal = 8.dp)) {
                    plans.forEachIndexed { i, _ -> TextButton({ selected = i }) { Text(if (i == 0) "Latest" else "#${plans.size - i}", fontWeight = if (selected == i) FontWeight.Bold else null) } }
                }
                val (tool, md) = plans[selected.coerceIn(0, plans.size - 1)]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(when (tool.status) { dsh.model.ToolStatus.Done -> "Approved / submitted"; dsh.model.ToolStatus.Rejected, dsh.model.ToolStatus.Error -> "Not approved"; else -> "Awaiting review" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { DshMarkdown(md) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Goal
// ------------------------------------------------------------------------------------------

@Composable
fun GoalPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    var goal by remember { mutableStateOf<JsonElement?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val projections by client.projections.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, reload, projections[sessionId]?.goal) {
        goal = runCatching { client.api.goals.get(sessionId) }.getOrNull()?.takeIf { it is JsonObject }
        loading = false
    }
    fun ref(): GoalRef? = goal?.let { g -> g["id"].str?.let { GoalRef(it, g["revision"].num ?: 0.0) } }
    fun act(what: String, f: suspend (GoalRef) -> Any?) { val r = ref() ?: return; scope.launch { client.guard(what) { f(r) }; reload++ } }

    PanelScaffold("Goal", onClose, docked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A goal keeps the agent working across turns until the objective is met.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val g = goal
            when {
                loading -> CircularProgressIndicator()
                g == null -> Button({ creating = true }) { Text("Set a goal") }
                else -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(g["objective"].str ?: "", style = MaterialTheme.typography.titleMedium)
                            Text("Phase: ${g["phase"].str} · ${g["activation"].str} · rounds ${g["roundsStarted"].long}/${g["maxGoalRounds"].long}", style = MaterialTheme.typography.bodySmall)
                            g["blockedReason"]["message"].str?.let { Text("Blocked: $it", color = MaterialTheme.colorScheme.error) }
                            val max = g["maxGoalRounds"].num ?: 0.0
                            if (max > 0) LinearProgressIndicator({ ((g["roundsStarted"].num ?: 0.0) / max).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val phase = g["phase"].str
                        if (phase == "active") OutlinedButton({ act("Pause goal") { client.api.goals.pause(sessionId, it) } }) { Text("Pause") }
                        if (phase == "paused" || phase == "blocked") OutlinedButton({ act("Resume goal") { client.api.goals.resume(sessionId, it) } }) { Text("Resume") }
                        if (phase != "complete") OutlinedButton({ act("Complete goal") { client.api.goals.complete(sessionId, it) } }) { Text("Mark complete") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ editing = true }) { Text("Edit") }
                        TextButton({ act("Clear goal") { client.api.goals.clear(sessionId, it) } }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (creating || editing) GoalDialog(
        title = if (creating) "Set goal" else "Edit goal",
        initialObjective = if (editing) goal["objective"].str ?: "" else "",
        initialRounds = if (editing) goal["maxGoalRounds"].long?.toString() ?: "" else "",
        onDismiss = { creating = false; editing = false },
    ) { objective, rounds ->
        val wasCreating = creating
        creating = false; editing = false
        scope.launch {
            if (wasCreating) client.guard("Set goal") { client.api.goals.create(sessionId, CreateGoalRequest(objective, rounds?.toDouble())) }
            else ref()?.let { r -> client.guard("Edit goal") { client.api.goals.edit(sessionId, r, EditGoalRequest(objective, rounds?.toDouble())) } }
            reload++
        }
    }
}

@Composable
private fun GoalDialog(title: String, initialObjective: String, initialRounds: String, onDismiss: () -> Unit, onSave: (String, Int?) -> Unit) {
    var objective by remember { mutableStateOf(initialObjective) }
    var rounds by remember { mutableStateOf(initialRounds) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(objective, { objective = it }, label = { Text("Objective") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(rounds, { rounds = it.filter(Char::isDigit) }, label = { Text("Max rounds (optional)") }, singleLine = true)
        }
    }, confirmButton = { TextButton({ onSave(objective.trim(), rounds.toIntOrNull()) }, enabled = objective.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------
// Subagents
// ------------------------------------------------------------------------------------------

@Composable
fun SubagentsPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    val projections by client.projections.collectAsState()
    val sessions by client.sessions.collectAsState()
    val catalog = projections[sessionId]?.subagentCatalog.orEmpty()
    val scope = rememberCoroutineScope()
    PanelScaffold("Subagents", onClose, docked) {
        if (catalog.isEmpty()) Placeholder("This session has not started any subagents.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(catalog.asReversed()) { e ->
                val id = e["id"].str ?: return@items
                val mode = e["mode"].str ?: "one-shot"
                val label = e["label"].str
                val running = sessions[id]?.running == true
                val title = projections[id]?.title
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable {
                    nav.subagent = SubagentRef(sessionId, id, mode, label ?: title)
                    if (!docked) nav.panel = null
                }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(label ?: title ?: "Subagent", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$mode · started ${relativeTime(e["createdAt"].long ?: 0)} ago" + if (running) " · running" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (running) IconButton({ scope.launch { client.guard("Interrupt subagent") { client.api.subagents.interruptByParent(id, sessionId, mode) } } }) {
                            Icon(Icons.Default.StopCircle, "Interrupt", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Background jobs
// ------------------------------------------------------------------------------------------

@Composable
fun JobsPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    var jobs by remember(sessionId) { mutableStateOf<List<JobFollowFrameOpenedJob>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId) {
        runCatching { client.api.job.list(JobListRequest(sessionId)).collect { f -> jobs = f.jobs } }
        if (jobs == null) jobs = emptyList()
    }
    val o = open
    if (o != null) { JobOutput(client, sessionId, o, jobs?.firstOrNull { it.id == o }, docked, onBack = { open = null }, onClose = onClose); return }
    PanelScaffold("Background jobs", onClose, docked) {
        val list = jobs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Placeholder("No background jobs in this session.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.sortedByDescending { it.startedAt }, key = { it.id }) { j ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable { open = j.id }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(when (j.status) { "running" -> LocalExtraColors.current.running; "failed", "killed" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary }, pulsing = j.status == "running")
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(j.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MonoStyle)
                                Text("${j.kind} · ${j.status}${j.progress?.let { " · $it" } ?: ""} · ${relativeTime(j.startedAt.toLong())} ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (j.status == "running") IconButton({ scope.launch { client.guard("Kill job") { client.api.job.kill(JobKillRequest(sessionId, j.id)) } } }) {
                                Icon(Icons.Default.StopCircle, "Kill", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobOutput(client: DshClient, sessionId: String, jobId: String, job: JobFollowFrameOpenedJob?, docked: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    var text by remember(jobId) { mutableStateOf("") }
    var status by remember(jobId) { mutableStateOf(job?.status) }
    LaunchedEffect(jobId) {
        val sb = StringBuilder()
        runCatching {
            client.api.job.follow(JobFollowRequest(sessionId, jobId)).collect { f ->
                when (f) {
                    is JobFollowFrame.Output -> { f.chunks.forEach { c -> if (c.gapBefore == true) sb.append("\n…\n"); sb.append(c.text) }; if (sb.length > 400_000) sb.deleteRange(0, sb.length - 300_000); text = stripAnsi(sb.toString()) }
                    is JobFollowFrame.Status -> status = f.job.status
                    is JobFollowFrame.Opened -> status = f.job.status
                    else -> Unit
                }
            }
        }
    }
    PanelScaffold(job?.label ?: "Job", onClose, docked, actions = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) {
        Column(Modifier.fillMaxSize()) {
            Text("Status: ${status ?: "…"}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            val scroll = rememberScrollState()
            LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp)) { CodeBlock(text.ifEmpty { "(no output yet)" }) }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Trajectory & stats
// ------------------------------------------------------------------------------------------

@Composable
fun TrajectoryPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    val projections by client.projections.collectAsState()
    val p = projections[sessionId] ?: SessionProjections()
    val stats = p.stats
    PanelScaffold("Trajectory & stats", onClose, docked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Usage")
            StatRow("Turns / steps", "${stats["turns"].long ?: 0} / ${stats["steps"].long ?: 0}")
            StatRow("Input tokens (incl. cache)", formatTokens(p.inputTokens))
            StatRow("Cache read", formatTokens(p.values["tokenUsage"]["cacheReadTokens"].long))
            StatRow("Output tokens", formatTokens(p.outputTokens))
            StatRow("Context", "${formatTokens(p.contextUsed)} / ${formatTokens(p.contextWindow)}")
            p.values["contextBreakdown"]?.let { b ->
                StatRow("  system / tools / messages", "${formatTokens(b["systemTokens"].long)} / ${formatTokens(b["toolsTokens"].long)} / ${formatTokens(b["messageTokens"].long)}")
            }
            SectionTitle("Timing")
            StatRow("Model time", ms(stats["llmMs"].long))
            StatRow("Tool time", ms(stats["toolMs"].long))
            val ttftSteps = stats["ttftSteps"].long ?: 0
            StatRow("Avg time to first token", if (ttftSteps > 0) ms((stats["ttftMs"].long ?: 0) / ttftSteps) else "–")
            val dm = stats["decodeMs"].long ?: 0; val dt = stats["decodeTokens"].long ?: 0
            StatRow("Decode speed", if (dm > 0) "${dt * 1000 / dm} tok/s" else "–")
            SectionTitle("Turns")
            p.turnOutline.asReversed().forEach { t ->
                var open by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Turn ${t["turn"].long}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    Text(t["prompt"].str ?: "", maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (open) t["response"].str?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoStyle)
    }
}

private fun ms(v: Long?): String = when {
    v == null -> "–"
    v >= 60_000 -> "${v / 60_000}m ${(v % 60_000) / 1000}s"
    v >= 1000 -> "${v / 100 / 10.0}s"
    else -> "${v}ms"
}
