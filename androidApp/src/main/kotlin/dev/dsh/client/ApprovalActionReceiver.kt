package dev.dsh.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Handles "Allow once" / "Reject" taps on approval notifications. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra("eventId") ?: return
        val value = intent.getStringExtra("value") ?: return
        val nid = intent.getStringExtra("notificationId")
        val app = context.applicationContext as DshApplication
        val client = app.appState.client.value ?: return
        val w = client.waterfalls.value.firstOrNull { it.eventId == eventId } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { client.conn.answerWaterfall(w, JsonPrimitive(value)) }
                nid?.let { NotificationManagerCompat.from(context).cancel(it.hashCode()) }
            } finally { pending.finish() }
        }
    }
}
