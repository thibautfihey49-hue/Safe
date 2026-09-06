package com.mysafe.mysafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class MySafeAgentService : android.app.Service() {
    companion object {
        const val ACTION_SEND_COMMAND = "com.mysafe.mysafe.SEND_COMMAND"
        const val EXTRA_TARGET = "target_number"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "MySafeAgentService"
        private const val NOTIF_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        creerCanalNotification()
        val notif = creerNotification()
        
        // ✅ VERIFICATION PERMISSIONS AVANT DEMARRAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            val hasLocation = ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasForegroundLoc = ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasLocation || !hasForegroundLoc) {
                Log.e("MySafeAgent", "Permissions manquantes !")
                stopSelf()
                return
            }
            
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_COMMAND -> {
                val cible = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                envoyerSMSData(cible, cmd)
                Log.d("MySafeAgent", "Commande envoyee a $cible : $cmd")
            }
        }
        return START_NOT_STICKY
    }

    private fun envoyerSMSData(destinataire: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(destinataire, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(destinataire, null, parts, null, null)
            }
        } catch (e: Exception) {
            Log.e("MySafeAgent", "Erreur envoi SMS: ${e.message}")
        }
    }

    private fun creerCanalNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "MySafe Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service de localisation par SMS"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(canal)
        }
    }

    private fun creerNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe Actif")
            .setContentText("Service de localisation en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
