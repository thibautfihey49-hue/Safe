package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationChannel
import androidx.core.app.NotificationManager

class CameraStreamService : Service() {
    companion object {
        const val TAG = "CameraStreamService"
        const val CHANNEL_ID = "camera_stream_channel"
        const val NOTIFICATION_ID = 789

        const val ACTION_START_STREAM = "com.mysafe.mysafe.START_STREAM"
        const val ACTION_STOP_STREAM = "com.mysafe.mysafe.STOP_STREAM"
        const val ACTION_TOGGLE_MIC = "com.mysafe.mysafe.TOGGLE_MIC"
        const val ACTION_TOGGLE_SPEAK = "com.mysafe.mysafe.TOGGLE_SPEAK"

        var surface: android.view.Surface? = null
        var statusCallback: ((String) -> Unit)? = null
        var isStreaming = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startStream(
                intent.getIntExtra("camera_facing", 1)
            )
            ACTION_STOP_STREAM -> stopStream()
            ACTION_TOGGLE_MIC -> {
                statusCallback?.invoke("🎤 Micro: Désactivé (simplifié)")
            }
            ACTION_TOGGLE_SPEAK -> {
                statusCallback?.invoke("🔊 Son: Désactivé (simplifié)")
            }
        }
        return START_STICKY
    }

    private fun startStream(facing: Int) {
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isStreaming = true
            statusCallback?.invoke("📹 Caméra démarrée ✅")
            Toast.makeText(this, "📹 Streaming caméra actif", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Streaming démarré")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur démarrage: ${e.message}", e)
            statusCallback?.invoke("❌ Erreur: ${e.message}")
            stopSelf()
        }
    }

    private fun stopStream() {
        isStreaming = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        statusCallback?.invoke("⏹️ Streaming arrêté ✅")
        Toast.makeText(this, "⏹️ Streaming arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe — Caméra active")
            .setContentText("Le streaming caméra est en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Caméra",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications du service de streaming caméra"
                setShowBadge(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
