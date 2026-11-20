package dev.dsh.client.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dsh.platform.DesktopKeyValueStore
import dsh.platform.KeyValueStore
import dsh.platform.PickedFile
import dsh.platform.PlatformServices
import dsh.platform.renderPdfDesktop
import dsh.ui.AppState
import dsh.ui.DshApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.file.Files

class DesktopPlatform : PlatformServices {
    override val name: String = System.getProperty("os.name") + " " + System.getProperty("os.arch")
    override val isMobile = false
    override val store: KeyValueStore = DesktopKeyValueStore()
    @Volatile var window: java.awt.Window? = null
    private var tray: TrayIcon? = null

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = withContext(Dispatchers.Main) {
        val d = FileDialog(window as? Frame, if (imagesOnly) "Attach images" else "Attach files", FileDialog.LOAD)
        d.isMultipleMode = true
        if (imagesOnly) d.setFilenameFilter { _, n -> n.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") }
        d.isVisible = true
        d.files.orEmpty().filter { it.isFile }.map { PickedFile(it.name, mediaTypeOf(it), it.readBytes()) }
    }

    override suspend fun saveFile(suggestedName: String, mediaType: String, bytes: ByteArray): String? = withContext(Dispatchers.Main) {
        val d = FileDialog(window as? Frame, "Save", FileDialog.SAVE)
        d.file = suggestedName
        d.isVisible = true
        val dir = d.directory ?: return@withContext null
        val f = File(dir, d.file ?: return@withContext null)
        withContext(Dispatchers.IO) { f.writeBytes(bytes) }
        f.absolutePath
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun notify(id: String, title: String, body: String) {
        if (window?.isFocused == true) return
        runCatching {
            if (!SystemTray.isSupported()) return
            val t = tray ?: TrayIcon(trayImage(), "DSH").also { it.isImageAutoSize = true; SystemTray.getSystemTray().add(it); tray = it }
            t.displayMessage(title, body, TrayIcon.MessageType.INFO)
        }
    }

    override suspend fun renderPdf(bytes: ByteArray, maxPages: Int, widthPx: Int): List<ByteArray>? =
        withContext(Dispatchers.IO) { runCatching { renderPdfDesktop(bytes, maxPages, widthPx) }.getOrNull() }

    fun dispose() { tray?.let { runCatching { SystemTray.getSystemTray().remove(it) } } }

    private fun mediaTypeOf(f: File): String = runCatching { Files.probeContentType(f.toPath()) }.getOrNull()
        ?: when (f.extension.lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "txt", "md", "log" -> "text/plain"; "json" -> "application/json"
            else -> "application/octet-stream"
        }

    private fun trayImage(): BufferedImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { img ->
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(0x4D, 0x6B, 0xFE); g.fillRoundRect(0, 0, 32, 32, 10, 10)
        g.color = java.awt.Color.WHITE; g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 20); g.drawString("D", 9, 24)
        g.dispose()
    }
}

/** App icon: rounded blue square with a white "D". */
val AppIcon: ImageVector = ImageVector.Builder("dsh", 48.dp, 48.dp, 48f, 48f).apply {
    path(fill = SolidColor(Color(0xFF4D6BFE))) {
        moveTo(10f, 0f); lineTo(38f, 0f); quadTo(48f, 0f, 48f, 10f); lineTo(48f, 38f); quadTo(48f, 48f, 38f, 48f)
        lineTo(10f, 48f); quadTo(0f, 48f, 0f, 38f); lineTo(0f, 10f); quadTo(0f, 0f, 10f, 0f); close()
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(14f, 11f); lineTo(24f, 11f); quadTo(37f, 11f, 37f, 24f); quadTo(37f, 37f, 24f, 37f); lineTo(14f, 37f); close()
        moveTo(20f, 17f); lineTo(20f, 31f); lineTo(24f, 31f); quadTo(31f, 31f, 31f, 24f); quadTo(31f, 17f, 24f, 17f); close()
    }
}.build()

fun main() {
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: if (System.getProperty("os.name").lowercase().contains("win")) "DIRECT3D" else "OPENGL")
    val platform = DesktopPlatform()
    application {
        val app = remember { AppState(platform) }
        val saved = remember { platform.store.get("window.size")?.split('x')?.mapNotNull { it.toIntOrNull() } }
        val state = rememberWindowState(
            size = if (saved?.size == 2) DpSize(saved[0].dp, saved[1].dp) else DpSize(1280.dp, 820.dp),
            position = WindowPosition.PlatformDefault,
        )
        var open by remember { mutableStateOf(true) }
        if (open) {
            Window(
                onCloseRequest = {
                    platform.store.put("window.size", "${state.size.width.value.toInt()}x${state.size.height.value.toInt()}")
                    platform.dispose(); open = false; exitApplication()
                },
                state = state, title = "DSH", icon = rememberVectorPainter(AppIcon),
            ) {
                platform.window = window
                window.minimumSize = java.awt.Dimension(420, 560)
                DshApp(app)
            }
        }
    }
}
