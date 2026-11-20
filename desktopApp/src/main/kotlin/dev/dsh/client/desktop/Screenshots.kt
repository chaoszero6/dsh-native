package dev.dsh.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dsh.platform.KeyValueStore
import dsh.platform.PickedFile
import dsh.platform.PlatformServices
import dsh.transport.Environment
import dsh.ui.AppState
import dsh.ui.DshApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Headless visual check: renders the real app (connected to a live DSH) into PNGs without a display.
 *   DSH_URL=… DSH_COOKIE=… ./gradlew :desktopApp:screenshots
 * Env: SHOT_DIR (output dir), SHOT_SESSION (session to open; default newest), SHOT_W/H, SHOT_THEME.
 */
fun main() = runBlocking {
    System.setProperty("java.awt.headless", "true")
    val out = File(System.getenv("SHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    val w = System.getenv("SHOT_W")?.toInt() ?: 1400
    val h = System.getenv("SHOT_H")?.toInt() ?: 880
    val mem = HashMap<String, String>()
    val platform = object : PlatformServices {
        override val name = "Screenshot harness"
        override val isMobile = (System.getenv("SHOT_MOBILE") == "1")
        override val store = object : KeyValueStore {
            override fun get(key: String) = mem[key]
            override fun put(key: String, value: String?) { if (value == null) mem.remove(key) else mem[key] = value }
        }
        override fun openUrl(url: String) {}
        override suspend fun pickFiles(imagesOnly: Boolean) = emptyList<PickedFile>()
        override suspend fun saveFile(suggestedName: String, mediaType: String, bytes: ByteArray): String? = null
        override fun copyToClipboard(text: String) {}
        override fun notify(id: String, title: String, body: String) {}
    }
    val app = withContext(Dispatchers.Main) { AppState(platform) }
    app.store.updatePrefs { it.copy(theme = System.getenv("SHOT_THEME") ?: "light") }

    fun shoot(name: String, settleMs: Long) {
        val scene = ImageComposeScene(w, h, Density(1f)) { DshApp(app) }
        val start = System.nanoTime()
        var t = 0L
        while ((System.nanoTime() - start) / 1_000_000 < settleMs) {
            scene.render(t); t += 16_000_000; Thread.sleep(16)
        }
        val img = scene.render(t)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote ${File(out, "$name.png").absolutePath}")
    }

    shoot("01-connect", 1500)
    val url = System.getenv("DSH_URL") ?: return@runBlocking
    val cookie = System.getenv("DSH_COOKIE") ?: return@runBlocking
    val (base, _) = Environment.parseInput(url)
    withContext(Dispatchers.Main) { app.activate(Environment("shot", "My DSH", base, cookie)) }
    shoot("02-sessions", 6000)
    val client = app.client.value!!
    val target = System.getenv("SHOT_SESSION") ?: client.sessions.value.values.filter { !it.blank && !it.isSubagent }.maxByOrNull { it.updatedAt }?.id
    app.pendingOpenSession.value = target
    shoot("03-chat", 9000)
    app.client.value?.close()
}
