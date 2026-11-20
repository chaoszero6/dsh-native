package dev.dsh.client

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service that keeps the process (and its WebSocket) alive while agents run or wait for input. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val summary = intent?.getStringExtra("summary") ?: "Connected to DSH"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, AndroidPlatform.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle("DSH").setContentText(summary)
            .setOngoing(true).setContentIntent(open).setSilent(true).build()
        runCatching {
            ServiceCompat.startForeground(this, 42, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
}
