package com.mysafe.mysafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        const val ACTION_PROCESS_COMMAND = "com.mysafe.mysafe.PROCESS_CMD"
        const val SMS_RECEIVED = "com.mysafe.mysafe.SMS_RECEIVED"
        private const val TAG = "MySafeAgent"
        private const val NOTIF_ID = 12345
        private const val CHANNEL_ID = "MySafeServiceChannel"
        private const val DISTANCE_THRESHOLD = 10f
        private const val TIME_THRESHOLD = 90000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var isTracking = false
    private var lastLocation: Location? = null
    private var lastSendTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processNewLocation(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ SERVICE CRÉÉ — onCreate()")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createNotificationChannel()
        startForegroundWithCheck()
    }

    private fun startForegroundWithCheck() {
        Log.d(TAG, "🔔 Démarrage service en premier plan...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "❌ Permission NOTIFICATIONS REFUSÉE !")
                return
            }
        }
        
        try {
            val notification = buildNotification()
            startForeground(NOTIF_ID, notification)
            Log.d(TAG, "✅ NOTIFICATION AFFICHÉE !")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERREUR startForeground : ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MySafe GPS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Suivi GPS en cours"
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            Log.d(TAG, "✅ Canal de notification créé")
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🔔 MySafe — ACTIF")
            .setContentText("Suivi GPS en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendDirectResponse(message: String) {
        Log.d(TAG, "📡 Réponse directe : $message")
        val intent = Intent(SMS_RECEIVED).apply {
            setPackage(packageName)
            putExtra("sms_message", message)
        }
        sendBroadcast(intent)
    }

    private fun sendSMSResponse(target: String, message: String) {
        try {
            smsManager.sendTextMessage(target, null, message, null, null)
            Log.d(TAG, "📤 SMS envoyé à $target : $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec envoi SMS : ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand reçu")
        intent?.let {
            if (it.action == ACTION_PROCESS_COMMAND) {
                val cmd = it.getStringExtra("command") ?: return@let
                val target = it.getStringExtra("sender_number") ?: return@let
                handleCommand(cmd, target)
            }
        }
        return START_STICKY
    }

    private fun handleCommand(command: String, target: String) {
        Log.d(TAG, "⚙️ Commande : $command pour $target")
        
        if (!hasLocationPermission()) {
            val msg = "!!ERREUR-AUTORISATION: Accorde la localisation"
            sendSMSResponse(target, msg)
            return
        }
        
        when (command.uppercase()) {
            "!!POSITION" -> sendPosition(target)
            "!!DEMARRER" -> startTracking(target)
            "!!STOP" -> stopTracking(target)
        }
    }

    private fun sendPosition(target: String) {
        val loc = getLastKnownLocation()
        if (loc == null) {
            sendSMSResponse(target, "!!POSITION_INCONNUE")
            return
        }
        val msg = "!!${String.format("%.6f", loc.latitude)},${String.format("%.6f", loc.longitude)},${String.format("%.1f", loc.altitude)}"
        sendSMSResponse(target, msg)
    }

    private fun startTracking(target: String) {
        Log.d(TAG, "▶️ Démarrage suivi...")
        if (isTracking) {
            sendSMSResponse(target, "!!OK-SUIVI")
            return
        }
        isTracking = true
        sendSMSResponse(target, "!!OK-SUIVI")
        
        getLastKnownLocation()?.let { loc ->
            val msg = "!!${String.format("%.6f", loc.latitude)},${String.format("%.6f", loc.longitude)},${String.format("%.1f", loc.altitude)}"
            sendSMSResponse(target, msg)
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, TIME_THRESHOLD, DISTANCE_THRESHOLD, locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, TIME_THRESHOLD, DISTANCE_THRESHOLD, locationListener
            )
            Log.d(TAG, "✅ Écoute GPS activée")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Impossible de démarrer le GPS : ${e.message}")
        }
    }

    private fun stopTracking(target: String) {
        Log.d(TAG, "⏹️ Arrêt suivi")
        isTracking = false
        locationManager.removeUpdates(locationListener)
        sendSMSResponse(target, "!!OK-STOP")
        lastLocation = null
    }

    private fun processNewLocation(location: Location) {
        if (!isTracking) return
        val now = System.currentTimeMillis()
        val last = lastLocation
        val shouldSend = when {
            last == null -> true
            location.distanceTo(last) >= DISTANCE_THRESHOLD -> true
            now - lastSendTime >= TIME_THRESHOLD -> true
            else -> false
        }
        if (!shouldSend) return
        
        val msg = "!!${String.format("%.6f", location.latitude)},${String.format("%.6f", location.longitude)},${String.format("%.1f", location.altitude)}"
        sendDirectResponse(msg)
        lastLocation = location
        lastSendTime = now
    }

    private fun getLastKnownLocation(): Location? {
        var best: Location? = null
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                locationManager.getLastKnownLocation(provider)?.let { loc ->
                    if (best == null || loc.accuracy < best!!.accuracy) best = loc
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Accès GPS refusé : ${e.message}")
        }
        return best
    }

    override fun onBind(intent: Intent?) = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 SERVICE DÉTRUIT")
        isTracking = false
        locationManager.removeUpdates(locationListener)
    }
}
