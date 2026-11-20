package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dsh.data.DshClient
import dsh.gen.WorkspaceByteReadOptions
import dsh.gen.WorkspaceByteReadOptionsRange
import dsh.gen.WorkspaceDirectoryListingEntriesItem
import dsh.gen.WorkspaceFileRange
import dsh.transport.str
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val OFFICE_EXT = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
private val MD_EXT = setOf("md", "markdown", "mdx")

@Composable
fun FilesPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) {
    val preview = nav.previewPath
    if (preview != null) {
        FilePreview(client, sessionId, preview, docked, onBack = { nav.previewPath = null }, onClose = onClose)
        return
    }
    var path by remember(sessionId) { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<WorkspaceDirectoryListingEntriesItem>?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(sessionId, path, reload) {
        error = null
        runCatching { client.api.workspaceFiles.list(sessionId, path) }
            .onSuccess { l -> entries = l.entries.sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() })); truncated = l.truncated }
            .onFailure { error = it.message; entries = emptyList() }
    }
    // Live refresh while the folder is open.
    LaunchedEffect(sessionId, path) {
        runCatching { client.api.workspaceFiles.changes(sessionId, path).collect { if (it is dsh.gen.WorkspaceFileWatchFrame.Change) reload++ } }
    }

    PanelScaffold("Files", onClose, docked, actions = { IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") } }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (path != ".") IconButton({ path = path.substringBeforeLast('/', ".").ifEmpty { "." } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                val crumbs = if (path == ".") emptyList() else path.split('/')
                TextButton({ path = "." }) { Text("workspace") }
                crumbs.forEachIndexed { i, c ->
                    Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ path = crumbs.take(i + 1).joinToString("/") }) { Text(c, maxLines = 1) }
                }
            }
            HorizontalDivider()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            val list = entries
            if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.name }) { e ->
                    val child = if (path == ".") e.name else "$path/${e.name}"
                    Row(Modifier.fillMaxWidth().clickable {
                        if (e.type == "directory") path = child else nav.previewPath = child
                    }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        Icon(when {
                            e.type == "directory" -> Icons.Default.Folder
                            e.type == "symlink" -> Icons.Default.Link
                            ext in IMAGE_EXT -> Icons.Default.Image
                            ext == "pdf" -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.Description
                        }, null, Modifier.size(18.dp), tint = if (e.type == "directory") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.size?.let { Text(formatBytes(it.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (truncated) item { Text("Listing truncated", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall) }
                if (list.isEmpty() && error == null) item { Text("Empty folder", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1 shl 30 -> "${(b * 10 / (1 shl 30)) / 10.0} GB"
    b >= 1 shl 20 -> "${(b * 10 / (1 shl 20)) / 10.0} MB"
    b >= 1 shl 10 -> "${(b * 10 / (1 shl 10)) / 10.0} KB"
    else -> "$b B"
}

/** Reads a whole file's bytes via repeated `workspaceFiles/readBytes` calls (base64 data). */
suspend fun readAllBytes(client: DshClient, sessionId: String, path: String, maxBytes: Long = 50L shl 20): ByteArray {
    val out = ArrayList<ByteArray>()
    var offset = 0L
    while (true) {
        val r = client.api.workspaceFiles.readBytes(sessionId, path, WorkspaceByteReadOptions(range = WorkspaceByteReadOptionsRange(offset = offset.toDouble())))
        val chunk = r.data.str?.let { Base64.decode(it) } ?: ByteArray(0)
        out += chunk
        offset += chunk.size
        if (r.eof || chunk.isEmpty() || offset >= maxBytes) break
    }
    val all = ByteArray(out.sumOf { it.size }); var p = 0
    out.forEach { it.copyInto(all, p); p += it.size }
    return all
}

private sealed interface PreviewContent {
    data class Text(val text: String, val truncated: Boolean, val markdown: Boolean) : PreviewContent
    data class Image(val bytes: ByteArray) : PreviewContent
    data class Pages(val pages: List<ByteArray>, val note: String?) : PreviewContent
    data class Unsupported(val reason: String) : PreviewContent
}

@Composable
fun FilePreview(client: DshClient, sessionId: String, path: String, docked: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    var content by remember(path) { mutableStateOf<PreviewContent?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var reload by remember(path) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val ext = path.substringAfterLast('.', "").lowercase()

    LaunchedEffect(path, reload) {
        error = null; content = null
        try {
            content = when {
                ext in IMAGE_EXT -> PreviewContent.Image(readAllBytes(client, sessionId, path))
                ext == "pdf" -> platform.renderPdf(readAllBytes(client, sessionId, path))?.let { PreviewContent.Pages(it, null) }
                    ?: PreviewContent.Unsupported("PDF preview is not supported on this device")
                ext in OFFICE_EXT -> {
                    val r = client.api.officeToPdf.render(sessionId, path, "foreground")
                    val pdf = Base64.decode(r.data.str ?: "")
                    platform.renderPdf(pdf)?.let { PreviewContent.Pages(it, r.missingFonts.takeIf { f -> f.isNotEmpty() }?.let { f -> "Missing fonts: ${f.joinToString()}" }) }
                        ?: PreviewContent.Unsupported("Document preview is not supported on this device")
                }
                else -> {
                    val t = client.api.workspaceFiles.read(sessionId, path, WorkspaceFileRange(offset = 0.0, limit = 4000.0))
                    PreviewContent.Text(t.text, !t.eof, ext in MD_EXT)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { error = e.message ?: e.toString() }
    }

    PanelScaffold(path.substringAfterLast('/'), onClose, docked, actions = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to files") }
        IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Reload") }
        IconButton({
            scope.launch {
                val bytes = client.guard("Download") { readAllBytes(client, sessionId, path) } ?: return@launch
                platform.saveFile(path.substringAfterLast('/'), "application/octet-stream", bytes)?.let { client.notice("Saved $it", isError = false) }
            }
        }) { Icon(Icons.Default.Download, "Download") }
    }) {
        Column(Modifier.fillMaxSize()) {
            Text(path, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            when (val c = content) {
                null -> if (error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is PreviewContent.Text -> if (c.markdown) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) { SelectionContainer { DshMarkdown(c.text) } }
                } else {
                    val lines = remember(c.text) { c.text.lines() }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize().background(LocalExtraColors.current.codeBg).horizontalScroll(rememberScrollState())) {
                            itemsIndexed(lines) { i, l ->
                                Row {
                                    Text("${i + 1}".padStart(5), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                                    Text(l, style = MonoStyle, softWrap = false, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
                                }
                            }
                            if (c.truncated) item { Text("… showing the first ${lines.size} lines", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                is PreviewContent.Image -> Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) { AsyncImage(c.bytes, path, Modifier.fillMaxSize()) }
                is PreviewContent.Pages -> LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.note?.let { n -> item { Text(n, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) } }
                    items(c.pages) { p -> AsyncImage(p, null, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
                }
                is PreviewContent.Unsupported -> Placeholder(c.reason)
            }
        }
    }
}
