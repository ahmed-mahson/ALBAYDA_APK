package com.albayda.court

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.albayda.court.data.AuthManager
import com.albayda.court.data.CaseRecordsRepository
import com.albayda.court.data.SyncManager

/** Keeps the shared database connection alive even when the app is in the background. */
class SyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("محكمة البيضاء الابتدائية")
            .setContentText("المزامنة التلقائية بين الأجهزة تعمل")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        AuthManager.init(this)
        SyncManager.init(this)
        CaseRecordsRepository.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مزامنة البيانات",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "albayda_sync"
        private const val NOTIFICATION_ID = 1001
    }
}
