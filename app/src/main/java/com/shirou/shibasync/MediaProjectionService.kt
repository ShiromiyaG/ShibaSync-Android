package com.shirou.shibasync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

open class MediaProjectionService : Service() {

    private val binder = MediaProjectionBinder()
    private val CHANNEL_ID = "MediaProjectionChannel"
    protected val NOTIFICATION_ID = 1

    inner class MediaProjectionBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service for internal audio capture"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    protected fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncMusic - Active Capture")
            .setContentText("Capturing internal audio...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun startCapture() {
        // Logic to start capture
    }

    open fun stopCapture() {
        // Logic to stop capture
        stopSelf()
    }
}
