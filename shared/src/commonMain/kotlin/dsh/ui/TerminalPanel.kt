package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.gen.TerminalCreateRequest
import dsh.gen.TerminalFrame
import dsh.gen.WebTerminalInfo
import dsh.gen.WebTerminalInfoShell
import dsh.term.VtScreen
import dsh.transport.newId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF16171C)
private val TermFg = Color(0xFFDADCE3)

@Composable
fun TerminalPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    var terminals by remember(sessionId) { mutableStateOf<List<WebTerminalInfo>?>(null) }
    var active by remember(sessionId) { mutableStateOf<String?>(null) }
    var shells by remember { mutableStateOf<List<WebTerminalInfoShell>>(emptyList()) }
    var newMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        terminals = runCatching { client.api.terminal.list(sessionId) }.onFailure { error = it.message }.getOrNull().orEmpty()
        if (active == null || terminals?.none { it.id == active } == true) active = terminals?.lastOrNull { it.state != "exited" }?.id ?: terminals?.lastOrNull()?.id
    }

    fun create(shell: String? = null) {
        creating = true; error = null
        scope.launch {
            val id = "t-" + newId().take(8)
            val info = client.guard("Open terminal") { client.api.terminal.create(sessionId, TerminalCreateRequest(shellPath = shell, id = id, cols = 100.0, rows = 30.0)) }
            if (info != null) { refresh(); active = info.id }
            creating = false
        }
    }

    LaunchedEffect(sessionId) {
        refresh()
        shells = runCatching { client.api.terminal.shells(sessionId) }.getOrNull().orEmpty()
        if (terminals.isNullOrEmpty()) create()
    }

    PanelScaffold("Terminal", onClose, docked, actions = {
        Box {
            IconButton({ if (shells.size > 1) newMenu = true else create() }, enabled = !creating) { Icon(Icons.Default.Add, "New terminal") }
            DropdownMenu(newMenu, { newMenu = false }) {
                shells.forEach { s -> DropdownMenuItem({ Text(s.name) }, { newMenu = false; create(s.path) }) }
            }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            val list = terminals
            if (!list.isNullOrEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { t ->
                    FilterChip(active == t.id, { active = t.id }, {
                        Text(t.title + if (t.state == "exited") " (exited)" else "", maxLines = 1)
                    }, trailingIcon = {
                        Icon(Icons.Default.Close, "Close", Modifier.size(14.dp).clickable {
                            scope.launch { client.guard("Close terminal") { client.api.terminal.close(sessionId, t.id) }; refresh() }
                        })
                    })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            val id = active
            when {
                id != null -> key(id) { TerminalView(client, sessionId, id) { scope.launch { refresh() } } }
                creating || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Placeholder("No terminal. Tap + to open one.")
            }
        }
    }
}

@Composable
private inline fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun TerminalView(client: DshClient, sessionId: String, id: String, onStateChange: () -> Unit) {
    val screen = remember(id) { VtScreen(100, 30) }
    var version by remember(id) { mutableLongStateOf(0L) }
    var info by remember(id) { mutableStateOf<WebTerminalInfo?>(null) }
    val attachmentId = remember(id) { "a-" + newId().take(12) }
    val input = remember(id) { Channel<String>(Channel.UNLIMITED) }
    val platform = LocalPlatform.current
    val focus = remember { FocusRequester() }
    var line by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Output stream.
    LaunchedEffect(id) {
        var failures = 0
        while (true) {
            try {
                client.api.terminal.follow(sessionId, id, attachmentId).collect { f ->
                    failures = 0
                    when (f) {
                        is TerminalFrame.Snapshot -> { screen.reset(); screen.resize(f.info.cols.toInt(), f.info.rows.toInt()); screen.feed(f.screen); info = f.info }
                        is TerminalFrame.Output -> screen.feed(f.data)
                        is TerminalFrame.State -> { info = f.info; onStateChange() }
                        else -> Unit
                    }
                    version = screen.version
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                failures++
                if (failures > 5) break
                delay(1000L * failures)
            }
        }
    }
    // Keep the terminal from idle reclamation while visible.
    LaunchedEffect(id) { runCatching { client.api.terminal.retain(sessionId, id).collect { } } }
    // Input pump: coalesce keystrokes into few write calls.
    LaunchedEffect(id) {
        for (first in input) {
            val sb = StringBuilder(first)
            while (true) { val more = input.tryReceive().getOrNull() ?: break; sb.append(more) }
            client.guard("Terminal input") { client.api.terminal.write(sessionId, id, attachmentId, sb.toString()) }
        }
    }
    DisposableEffect(id) { onDispose { input.close() } }
    fun send(s: String) { input.trySend(s) }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember { measurer.measure("W", MonoStyle) }

    Column(Modifier.fillMaxSize().background(TermBg)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
            val cols = with(density) { (maxWidth.toPx() / cell.size.width).toInt() }.coerceIn(20, 300)
            val rows = with(density) { (maxHeight.toPx() / cell.size.height).toInt() }.coerceIn(5, 200)
            LaunchedEffect(cols, rows, info?.state) {
                if (info == null || info?.state == "exited") return@LaunchedEffect
                delay(250)
                if (screen.cols != cols || screen.rows != rows) {
                    screen.resize(cols, rows); version = screen.version
                    client.guard("Resize terminal") { client.api.terminal.resize(sessionId, id, attachmentId, cols.toDouble(), rows.toDouble()) }
                }
            }
            val listState = rememberLazyListState()
            @Suppress("UNUSED_EXPRESSION") version
            val lines = screen.lines(history = 500)
            LaunchedEffect(version) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRequester(focus).focusable()
                    .clickable { runCatching { focus.requestFocus() } }
                    .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) keyToBytes(e)?.let { send(it); true } ?: false else false },
            ) {
                val cursorRow = lines.size - screen.rows + screen.cursorY
                itemsIndexed(lines) { i, l ->
                    Text(renderLine(l, if (i == cursorRow && screen.cursorVisible) screen.cursorX else -1), style = MonoStyle.copy(color = TermFg), softWrap = false, maxLines = 1)
                }
            }
        }
        if (info?.state == "exited") Text("Process exited${info?.exitCode?.let { " (code ${it.toInt()})" } ?: ""}", color = Color(0xFFFFB74D), modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium)
        // Special keys (essential on touch keyboards).
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF22242B)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun K(label: String, bytes: String) = TextButton({ send(bytes) }) { Text(label, color = TermFg, style = MonoStyle) }
            FilterChip(ctrl, { ctrl = !ctrl }, { Text("Ctrl", style = MonoStyle) })
            K("Esc", "\u001B"); K("Tab", "\t"); K("^C", "\u0003"); K("^D", "\u0004"); K("^Z", "\u001A"); K("^L", "\u000C")
            K("↑", "\u001B[A"); K("↓", "\u001B[B"); K("←", "\u001B[D"); K("→", "\u001B[C"); K("Home", "\u001B[H"); K("End", "\u001B[F")
            TextButton({ platform.copyToClipboard(screen.text()) }) { Text("Copy", color = TermFg) }
        }
        if (platform.isMobile || true) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(line, { v ->
                    if (ctrl && v.length == line.length + 1) {
                        val c = v.last().lowercaseChar()
                        if (c in 'a'..'z') send(((c - 'a') + 1).toChar().toString())
                        ctrl = false
                    } else line = v
                }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Type a command, Enter to run") },
                    textStyle = MonoStyle,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(line + "\r"); line = "" }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false))
                IconButton({ send(line + "\r"); line = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = TermFg) }
            }
        }
    }
}

private fun termColor(c: Int, isFg: Boolean): Color = if (c < 0) (if (isFg) TermFg else Color.Transparent) else Color(VtScreen.color256(c))

private fun renderLine(cells: Array<VtScreen.Cell>, cursorX: Int): AnnotatedString = buildAnnotatedString {
    var last = cells.size - 1
    while (last > cursorX && last >= 0 && cells[last].ch == ' ' && cells[last].style.bg < 0) last--
    var i = 0
    while (i <= last) {
        val st = cells[i].style
        val isCursor = i == cursorX
        var j = i + 1
        if (!isCursor) while (j <= last && cells[j].style == st && j != cursorX) j++
        var fg = termColor(if (st.bold && st.fg in 0..7) st.fg + 8 else st.fg, true)
        var bg = termColor(st.bg, false)
        if (st.inverse || isCursor) { val t = fg; fg = if (bg == Color.Transparent) TermBg else bg; bg = t }
        withStyle(SpanStyle(color = if (st.dim) fg.copy(alpha = 0.6f) else fg, background = bg,
            fontWeight = if (st.bold) FontWeight.Bold else null, fontStyle = if (st.italic) FontStyle.Italic else null,
            textDecoration = if (st.underline) TextDecoration.Underline else null)) {
            for (k in i until j) append(cells[k].ch)
        }
        i = j
    }
    if (cursorX > last && cursorX >= 0) {
        append(" ".repeat((cursorX - last - 1).coerceAtLeast(0)))
        withStyle(SpanStyle(background = TermFg, color = TermBg)) { append(' ') }
    }
}

/** Maps a desktop key press to terminal input bytes. */
private fun keyToBytes(e: androidx.compose.ui.input.key.KeyEvent): String? {
    if (e.isCtrlPressed) {
        val cp = e.utf16CodePoint
        val c = if (cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code) cp.toChar().lowercaseChar() else null
        if (c != null) return ((c - 'a') + 1).toChar().toString()
    }
    return when (e.key) {
        Key.Enter, Key.NumPadEnter -> "\r"
        Key.Backspace -> "\u007F"
        Key.Tab -> "\t"
        Key.Escape -> "\u001B"
        Key.DirectionUp -> "\u001B[A"
        Key.DirectionDown -> "\u001B[B"
        Key.DirectionRight -> "\u001B[C"
        Key.DirectionLeft -> "\u001B[D"
        Key.MoveHome -> "\u001B[H"
        Key.MoveEnd -> "\u001B[F"
        Key.Delete -> "\u001B[3~"
        Key.PageUp -> "\u001B[5~"
        Key.PageDown -> "\u001B[6~"
        else -> e.utf16CodePoint.takeIf { it >= 32 }?.let { Char(it).toString() }
    }
}
