package dev.dsh.client

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dsh.ui.DshApp

class MainActivity : ComponentActivity() {
    private val app get() = application as DshApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.platform.attach(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent { DshApp(app.appState) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); app.platform.foreground = true }
    override fun onStop() { super.onStop(); app.platform.foreground = false }

    /** Text shared from other apps lands on the clipboard-free path: copied into the composer via the clipboard is avoided; we keep it simple. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("openSession")?.let { app.appState.pendingOpenSession.value = it }
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uris = buildList<Uri> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            }
            dsh.ui.SharedInbox.offer(text, uris.mapNotNull { app.platform.readUri(it) })
        }
    }
}
