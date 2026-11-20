package dev.dsh.client

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dsh.platform.AndroidKeyValueStore
import dsh.platform.KeyValueStore
import dsh.platform.PickedFile
import dsh.platform.PlatformServices
import dsh.platform.renderPdfAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class AndroidPlatform(private val ctx: Context) : PlatformServices {
    override val name: String = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})"
    override val isMobile = true
    override val store: KeyValueStore = AndroidKeyValueStore(ctx)

    private var activityRef = WeakReference<MainActivity>(null)
    @Volatile var foreground = false
    private var pickResult: CompletableDeferred<List<Uri>>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private var pickLauncher: ActivityResultLauncher<Array<String>>? = null
    private var saveLauncher: ActivityResultLauncher<String>? = null
    private var pendingSaveMime = "application/octet-stream"

    init { createChannels() }

    /** Must be called from Activity.onCreate (launchers are registered before STARTED). */
    fun attach(activity: MainActivity) {
        activityRef = WeakReference(activity)
        pickLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pickResult?.complete(uris); pickResult = null
        }
        saveLauncher = activity.registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).setType(pendingSaveMime)
        }) { uri -> saveResult?.complete(uri); saveResult = null }
    }

    override fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> {
        val launcher = pickLauncher ?: return emptyList()
        val d = CompletableDeferred<List<Uri>>()
        pickResult = d
        withContext(Dispatchers.Main) { launcher.launch(if (imagesOnly) arrayOf("image/*") else arrayOf("*/*")) }
        val uris = d.await()
        return withContext(Dispatchers.IO) { uris.mapNotNull { readUri(it) } }
    }

    fun readUri(uri: Uri): PickedFile? = runCatching {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment ?: "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        PickedFile(name, mime, bytes)
    }.getOrNull()

    override suspend fun saveFile(suggestedName: String, mediaType: String, bytes: ByteArray): String? {
        val launcher = saveLauncher ?: return null
        val d = CompletableDeferred<Uri?>()
        saveResult = d
        pendingSaveMime = mediaType
        withContext(Dispatchers.Main) { launcher.launch(suggestedName) }
        val uri = d.await() ?: return null
        withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        return suggestedName
    }

    override fun copyToClipboard(text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DSH", text))
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun notify(id: String, title: String, body: String) {
        if (foreground || !canNotify()) return
        val open = PendingIntent.getActivity(ctx, id.hashCode(), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val isApproval = id.startsWith("wf-") && body.startsWith("Approval")
        val b = NotificationCompat.Builder(ctx, if (id.startsWith("wf-")) CH_ATTENTION else CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setContentIntent(open)
            .setPriority(if (id.startsWith("wf-")) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (isApproval) {
            val eventId = id.removePrefix("wf-")
            for ((label, value) in listOf("Allow once" to "allowed-once", "Reject" to "rejected")) {
                val pi = PendingIntent.getBroadcast(ctx, (eventId + value).hashCode(),
                    Intent(ctx, ApprovalActionReceiver::class.java).putExtra("eventId", eventId).putExtra("value", value).putExtra("notificationId", id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                b.addAction(0, label, pi)
            }
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(id.hashCode(), b.build())
    }

    override fun cancelNotification(id: String) = NotificationManagerCompat.from(ctx).cancel(id.hashCode())

    override fun setBackgroundActivity(active: Boolean, summary: String) {
        val i = Intent(ctx, KeepAliveService::class.java)
        if (active) {
            i.putExtra("summary", summary)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        } else ctx.stopService(i)
    }

    override suspend fun renderPdf(bytes: ByteArray, maxPages: Int, widthPx: Int): List<ByteArray>? =
        withContext(Dispatchers.IO) { runCatching { renderPdfAndroid(ctx.cacheDir, bytes, maxPages, widthPx) }.getOrNull() }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Needs your input", NotificationManager.IMPORTANCE_HIGH).apply { description = "Approvals and agent questions" })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW).apply { description = "Keeps the connection open while agents are working" })
    }

    companion object {
        const val CH_ATTENTION = "attention"
        const val CH_DONE = "done"
        const val CH_SERVICE = "service"
    }
}
