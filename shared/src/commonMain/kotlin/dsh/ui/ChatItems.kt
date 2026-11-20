package dsh.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import dsh.data.ConversationController
import dsh.data.DshClient
import dsh.model.ChatItem
import dsh.model.Draft
import dsh.model.Part
import dsh.model.ToolStatus
import dsh.transport.PrettyJson
import dsh.transport.get
import dsh.transport.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ChatItemView(item: ChatItem, conv: ConversationController, client: DshClient, nav: MainNav, sessionId: String) {
    when (item) {
        is ChatItem.User -> UserBubble(item, conv)
        is ChatItem.Assistant -> AssistantBlock(item.parts, conv, streaming = false)
        is ChatItem.Tool -> ToolCard(item, nav, sessionId)
        is ChatItem.Notice -> NoticeLine(item.text, item.tone)
        is ChatItem.Todos -> TodoCard(item.todos.map { it.content to it.status })
        is ChatItem.TurnEnd -> NoticeLine(
            when (item.reason) { "interrupted" -> "Turn stopped"; "error" -> "Turn failed: ${item.error ?: "error"}"; "max-tokens" -> "Stopped: output token limit reached"; "blocked" -> "Turn blocked"; "forked" -> "Session forked here"; else -> "Turn ended (${item.reason})" },
            if (item.reason == "error") ChatItem.Tone.Error else ChatItem.Tone.Warn,
        )
        is ChatItem.Compaction -> CollapsibleCard(Icons.Default.Inventory2, "Context compacted" + (item.tokens?.let { " · ${formatTokens(it)} tokens summarized" } ?: "")) {
            SelectionContainer { Markdown(item.summary) }
        }
        is ChatItem.Command -> CommandLine(item)
        is ChatItem.Deliverables -> DeliverablesCard(item, nav)
        is ChatItem.Raw -> CollapsibleCard(Icons.Default.Description, item.type, subtle = true) {
            CodeBlock(item.data?.let { PrettyJson.encodeToString(JsonElement.serializer(), it) } ?: "null", maxLines = 200)
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, conv: ConversationController) {
    val platform = LocalPlatform.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 640.dp)) {
            Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in item.parts) when (p) {
                        is Part.Text -> SelectionContainer { Text(p.text, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        is Part.Image -> AttachmentImage(conv, p)
                        is Part.File -> Text("📎 ${p.name}", style = MaterialTheme.typography.bodySmall)
                        else -> Unit
                    }
                }
            }
            Row {
                Text(relativeTime(item.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
                Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(14.dp).clickable { platform.copyToClipboard(item.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }) }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AttachmentImage(conv: ConversationController, p: Part.Image) {
    var bytes by remember(p.attachmentId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(p.attachmentId) { if (!p.offloaded) bytes = conv.attachmentBytes(p.attachmentId) }
    val b = bytes
    if (b != null) AsyncImage(model = b, contentDescription = p.name, modifier = Modifier.heightIn(max = 280.dp).widthIn(max = 360.dp).clip(RoundedCornerShape(10.dp)))
    else Text("🖼 ${p.name ?: "image"} ${if (p.width > 0) "(${p.width}×${p.height})" else ""}", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun AssistantBlock(parts: List<Part>, conv: ConversationController?, streaming: Boolean) {
    val platform = LocalPlatform.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in parts) when (p) {
            is Part.Reasoning -> if (p.text.isNotBlank()) ReasoningView(p.text, streaming)
            is Part.Text -> if (p.text.isNotBlank()) SelectionContainer { Markdown(p.text, modifier = Modifier.fillMaxWidth()) }
            is Part.Image -> if (conv != null) AttachmentImage(conv, p)
            is Part.ToolCallPart -> if (streaming) ToolHeaderLine(Icons.Default.Build, p.name, summarizeArgsRaw(p.name, p.arguments), ToolStatus.Pending)
            else -> Unit
        }
        if (!streaming) {
            val text = parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }
            if (text.isNotBlank()) Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(14.dp).clickable { platform.copyToClipboard(text) }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DraftView(d: Draft) {
    Column {
        AssistantBlock(d.toParts(), null, streaming = true)
        if (d.blocks.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("Thinking…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReasoningView(text: String, streaming: Boolean) {
    var open by remember { mutableStateOf(streaming) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Icon(Icons.Default.Psychology, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (streaming) " Thinking…" else " Reasoning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Text(
            if (streaming) text.takeLast(3000) else text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}

@Composable
fun NoticeLine(text: String, tone: ChatItem.Tone = ChatItem.Tone.Info) {
    val color = when (tone) { ChatItem.Tone.Info -> MaterialTheme.colorScheme.onSurfaceVariant; ChatItem.Tone.Warn -> LocalExtraColors.current.warn; ChatItem.Tone.Error -> MaterialTheme.colorScheme.error }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (tone != ChatItem.Tone.Info) Icon(if (tone == ChatItem.Tone.Error) Icons.Default.Error else Icons.Default.Warning, null, Modifier.size(14.dp), tint = color)
        Text(" $text", style = MaterialTheme.typography.labelMedium, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CommandLine(c: ChatItem.Command) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(c.line, style = MonoStyle, color = MaterialTheme.colorScheme.primary)
        c.result?.let { Text("  → $it", style = MaterialTheme.typography.labelMedium, color = if (c.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun TodoCard(todos: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Checklist, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(" Todos · ${todos.count { it.second == "completed" }}/${todos.size}", style = MaterialTheme.typography.labelLarge)
            }
            for ((content, status) in todos) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status) { "completed" -> Icons.Default.TaskAlt; "in_progress" -> Icons.Default.HourglassTop; else -> Icons.Default.RadioButtonUnchecked },
                    null, Modifier.size(16.dp),
                    tint = when (status) { "completed" -> MaterialTheme.colorScheme.tertiary; "in_progress" -> LocalExtraColors.current.running; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
                Text(" $content", style = MaterialTheme.typography.bodySmall,
                    color = if (status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DeliverablesCard(d: ChatItem.Deliverables, nav: MainNav) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Delivered files", style = MaterialTheme.typography.labelLarge)
            for ((path, desc) in d.files) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { nav.previewPath = path; nav.panel = SidePanel.Files }.padding(4.dp)) {
                Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                Column(Modifier.padding(start = 6.dp)) {
                    Text(path, style = MonoStyle)
                    desc?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun CollapsibleCard(icon: ImageVector, title: String, subtle: Boolean = false, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).animateContentSize(),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Text(" $title", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        if (open) Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { content() }
    }
}

// ------------------------------------------------------------------------------------------
// Tool cards
// ------------------------------------------------------------------------------------------

private fun toolIcon(name: String): ImageVector = when {
    name in setOf("bash", "shell", "exec", "run_command") || name.contains("terminal") || name.contains("shell") -> Icons.Default.Terminal
    name.contains("edit") || name.contains("write") || name.contains("patch") || name.contains("replace") -> Icons.Default.EditNote
    name.contains("read") || name.contains("view") || name.contains("cat") -> Icons.Default.Description
    name.contains("grep") || name.contains("glob") || name.contains("search") || name.contains("find") || name.contains("ls") -> Icons.Default.FindInPage
    name.contains("web") || name.contains("fetch") || name.contains("browser") -> Icons.Default.Language
    name.contains("todo") -> Icons.Default.Checklist
    name.contains("ask") || name.contains("question") -> Icons.Default.QuestionAnswer
    name.contains("agent") || name.contains("subagent") || name.contains("task") || name.contains("team") -> Icons.Default.AccountTree
    name.contains("schedule") || name.contains("cron") -> Icons.Default.Schedule
    else -> Icons.Default.Build
}

private fun JsonObject?.s(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content }

fun summarizeArgs(name: String, args: JsonObject?): String {
    if (args == null) return ""
    return args.s("description")?.let { d -> args.s("command", "cmd")?.let { "$d — $it" } ?: d }
        ?: args.s("command", "cmd", "file_path", "path", "filePath", "pattern", "query", "url", "prompt", "objective", "name", "title")
        ?: args.entries.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }?.let { (it.value as JsonPrimitive).content }
        ?: ""
}

private fun summarizeArgsRaw(name: String, raw: String): String =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()?.let { summarizeArgs(name, it) }
        ?: raw.take(120)

@Composable
private fun ToolHeaderLine(icon: ImageVector, name: String, summary: String, status: ToolStatus, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $name", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("  $summary", Modifier.weight(1f), style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (status) {
            ToolStatus.Pending, ToolStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ToolStatus.AwaitingApproval -> Icon(Icons.Default.HourglassTop, "Awaiting approval", Modifier.size(16.dp), tint = LocalExtraColors.current.warn)
            ToolStatus.Done -> Icon(Icons.Default.CheckCircle, "Done", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            ToolStatus.Error -> Icon(Icons.Default.Error, "Error", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            ToolStatus.Rejected -> Icon(Icons.Default.Block, "Rejected", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
        trailing()
    }
}

@Composable
fun ToolCard(t: ChatItem.Tool, nav: MainNav, sessionId: String) {
    val args = remember(t.arguments) { t.args }
    if (t.name == "todo_write" || t.name == "TodoWrite") {
        val todos = (args?.get("todos") as? kotlinx.serialization.json.JsonArray)?.map { (it["content"].str ?: "") to (it["status"].str ?: "pending") }
        if (todos != null) { TodoCard(todos); return }
    }
    var open by remember(t.key) { mutableStateOf(t.status == ToolStatus.AwaitingApproval || t.status == ToolStatus.Error) }
    val border = when (t.status) {
        ToolStatus.Error, ToolStatus.Rejected -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        ToolStatus.AwaitingApproval -> LocalExtraColors.current.warn
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp)).animateContentSize()) {
        Box(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp)) {
            ToolHeaderLine(toolIcon(t.name), t.name, summarizeArgs(t.name, args), t.status) {
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
        }
        t.approvalReason?.takeIf { t.status == ToolStatus.AwaitingApproval }?.let {
            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.warn)
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolBody(t, args, nav, sessionId)
        }
    }
}

@Composable
private fun ToolBody(t: ChatItem.Tool, args: JsonObject?, nav: MainNav, sessionId: String) {
    val name = t.name.lowercase()
    val command = args.s("command", "cmd")
    val oldStr = args.s("old_string", "oldString", "old_str")
    val newStr = args.s("new_string", "newString", "new_str")
    val content = args.s("content", "file_text")
    val path = args.s("file_path", "path", "filePath")
    when {
        command != null && (name.contains("bash") || name.contains("shell") || name.contains("exec") || name.contains("command")) -> {
            CodeBlock("$ $command", maxLines = 30)
        }
        oldStr != null || newStr != null -> {
            path?.let { PathLink(it, nav) }
            DiffView(oldStr.orEmpty(), newStr.orEmpty())
        }
        content != null && (name.contains("write") || name.contains("create")) -> {
            path?.let { PathLink(it, nav) }
            CodeBlock(content.lines().take(80).joinToString("\n") + if (content.lines().size > 80) "\n… (${content.lines().size} lines)" else "", maxLines = 82)
        }
        else -> {
            path?.let { PathLink(it, nav) }
            if (args != null && args.isNotEmpty()) CodeBlock(PrettyJson.encodeToString(JsonElement.serializer(), args), maxLines = 40)
            else if (t.arguments.isNotBlank() && t.arguments != "{}") CodeBlock(t.arguments, maxLines = 40)
        }
    }
    if (t.error != null) Text(t.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (t.result.isNotEmpty()) {
        val text = t.resultText
        var full by remember(t.key) { mutableStateOf(false) }
        val lines = text.lines()
        val shown = if (full || lines.size <= 40) text else lines.take(40).joinToString("\n")
        if (text.isNotBlank()) {
            CodeBlock(stripAnsi(shown), color = if (t.status == ToolStatus.Error) MaterialTheme.colorScheme.error else Color.Unspecified)
            if (lines.size > 40) TextButton({ full = !full }) { Text(if (full) "Show less" else "Show all ${lines.size} lines") }
        }
        t.result.filterIsInstance<Part.Image>().forEach { Text("🖼 ${it.name ?: "image"}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PathLink(path: String, nav: MainNav) {
    Text(path, style = MonoStyle, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { nav.previewPath = path; nav.panel = SidePanel.Files }.padding(2.dp))
}

/** Simple line diff (LCS) between two snippets, rendered with +/- coloring. */
@Composable
fun DiffView(old: String, new: String) {
    val lines = remember(old, new) { lineDiff(old.lines(), new.lines()) }
    UnifiedDiff(lines)
}

@Composable
fun UnifiedDiff(lines: List<String>, maxLines: Int = 400) {
    val extra = LocalExtraColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(extra.codeBg).padding(vertical = 6.dp)) {
        for (l in lines.take(maxLines)) {
            val bg = when { l.startsWith("+") -> extra.diffAdd; l.startsWith("-") -> extra.diffDel; else -> Color.Transparent }
            Text(l, style = MonoStyle, softWrap = true, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp))
        }
        if (lines.size > maxLines) Text("… ${lines.size - maxLines} more lines", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
    }
}

fun lineDiff(a: List<String>, b: List<String>): List<String> {
    if (a.size * b.size > 400_000) return a.map { "-$it" } + b.map { "+$it" }
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed())
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val out = ArrayList<String>()
    var i = 0; var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { out += " ${a[i]}"; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out += "-${a[i]}"; i++ }
            else -> { out += "+${b[j]}"; j++ }
        }
    }
    while (i < a.size) out += "-${a[i++]}"
    while (j < b.size) out += "+${b[j++]}"
    return out
}
