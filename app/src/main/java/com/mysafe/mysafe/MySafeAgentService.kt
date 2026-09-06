package com.mysafe.mysafe

import android.Manifest
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
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class MySafeAgentService : Service() {
    companion object {
        private const val TAG = "MySafe-Agent"
        const val SMS_RECEIVED = "com.mysafe.mysafe.SMS_RECEIVED"
        const val ACTION_SEND_COMMAND = "com.mysafe.mysafe.SEND_COMMAND"
        private const val CHANNEL_ID = "MySafeServiceChannel"
        
        // 🎯 PRÉCISION
        private const val TARGET_ACCURACY = 3f
        private const val MAX_ACCEPTED_ACCURACY = 8f
        
        // 🎯 SUIVI CONTINU — TES RÉGLAGES !
        private const val SUIVI_INTERVALLE_MS = 90000L    // ⏳ 1 minute 30 = 90 secondes
        private const val SUIVI_DISTANCE_MIN_METRES = 20f // 📏 20 mètres minimum
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequestSuivi: LocationRequest
    private lateinit var locationRequestPonctuel: LocationRequest
    private lateinit var locationManager: LocationManager
    private var locationCallback: LocationCallback? = null
    private var gpsListener: LocationListener? = null
    private var targetNumber: String = ""
    private var isTracking = false
    private var bestLocation: Location? = null
    private var locationStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // 🎯 DEMANDE PONCTUELLE — Rapide et précise
        locationRequestPonctuel = LocationRequest.create().apply {
            interval = 3000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            maxWaitTime = 5000
        }
        
        // 🎯 SUIVI CONTINU — 1min30 + 20m
        locationRequestSuivi = LocationRequest.create().apply {
            interval = SUIVI_INTERVALLE_MS          // ⏳ 90 secondes entre chaque
            fastestInterval = SUIVI_INTERVALLE_MS  // Pas plus vite que 90s
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = SUIVI_DISTANCE_MIN_METRES  // 📏 20m minimum
            maxWaitTime = SUIVI_INTERVALLE_MS
        }
        
        startGpsNativeListener()
    }

    private fun startGpsNativeListener() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        gpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
    }

    private fun checkLocation(loc: Location) {
        val now = System.currentTimeMillis()
        if (now - loc.time > 60000) return
        
        if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
            bestLocation = loc
        }
        
        if (bestLocation!!.accuracy <= TARGET_ACCURACY) {
            if (!isTracking) sendBestLocation()
            return
        }
        
        if (locationStartTime > 0 && now - locationStartTime > 20000) {
            if (!isTracking) sendBestLocation()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_COMMAND -> {
                val target = intent.getStringExtra("target_number") ?: return START_NOT_STICKY
                val command = intent.getStringExtra("command") ?: return START_NOT_STICKY
                targetNumber = normalizeNumber(target)
                handleOutgoingCommand(targetNumber, command)
            }
            SMS_RECEIVED -> {
                val message = intent.getStringExtra("sms_message") ?: return START_NOT_STICKY
                val sender = intent.getStringExtra("sender_number") ?: return START_NOT_STICKY
                handleIncomingCommand(message, normalizeNumber(sender))
            }
        }
        return START_STICKY
    }

    private fun handleIncomingCommand(message: String, senderNumber: String) {
        Log.d(TAG, "📩 Commande reçue de [$senderNumber] : [$message]")
        
        when {
            message == "!!POSITION" -> {
                Log.d(TAG, "📍 Position ponctuelle — Recherche précision ${TARGET_ACCURACY}m...")
                targetNumber = senderNumber
                bestLocation = null
                locationStartTime = System.currentTimeMillis()
                requestFreshLocation()
            }
            message == "!!DEMARRER" -> {
                Log.d(TAG, "🔔 SUIVI DÉMARRÉ — Intervalle: ${SUIVI_INTERVALLE_MS/1000}s | Distance min: ${SUIVI_DISTANCE_MIN_METRES}m")
                targetNumber = senderNumber
                isTracking = true
                startLocationUpdates()
            }
            message == "!!STOP" -> {
                Log.d(TAG, "🛑 SUIVI ARRÊTÉ")
                isTracking = false
                stopLocationUpdates()
            }
            message.startsWith("!!") && message.contains(",") -> {
                val uiIntent = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
                    setPackage(packageName)
                    putExtra("sms_message", message)
                }
                sendBroadcast(uiIntent)
            }
        }
    }

    private fun handleOutgoingCommand(target: String, command: String) {
        Log.d(TAG, "📤 Envoi commande '$command' à $target")
        sendSms(target, command)
    }

    private fun requestFreshLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        fusedLocationClient.requestLocationUpdates(locationRequestPonctuel, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { checkLocation(it) }
            }
        }, mainLooper)
    }

    private fun sendBestLocation() {
        val loc = bestLocation ?: return
        val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()},${loc.accuracy.toInt()}m"
        Log.d(TAG, "📤 POSITION ENVOYÉE: $response")
        sendSms(targetNumber, response)
        locationStartTime = 0
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (loc.accuracy <= MAX_ACCEPTED_ACCURACY) {
                        val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()},${loc.accuracy.toInt()}m"
                        Log.d(TAG, "📤 [SUIVI] Déplacement détecté (>${SUIVI_DISTANCE_MIN_METRES}m) — Envoi: $response")
                        sendSms(targetNumber, response)
                    } else {
                        Log.d(TAG, "⏭️ [SUIVI] Position ignorée — précision ${loc.accuracy.toInt()}m > ${MAX_ACCEPTED_ACCURACY}m")
                    }
                }
            }
        }
        
        Log.d(TAG, "🔄 Démarrage suivi — Intervalle: ${SUIVI_INTERVALLE_MS/1000}s, Distance: ${SUIVI_DISTANCE_MIN_METRES}m")
        fusedLocationClient.requestLocationUpdates(locationRequestSuivi, locationCallback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    private fun normalizeNumber(s: String) = s.replace("\\s".toRegex(), "").replace("-", "").let {
        if (it.startsWith("+")) it else if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it
    }

    private fun sendSms(to: String, message: String) {
        try {
            val manager = SmsManager.getDefault()
            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                manager.sendTextMessage(to, null, message, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ ÉCHEC ENVOI SMS à $to : ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe GPS")
            .setContentText("Suivi actif — ${SUIVI_INTERVALLE_MS/1000}s / ${SUIVI_DISTANCE_MIN_METRES}m")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MySafe GPS Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Service de localisation — Suivi configurable"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        gpsListener?.let { locationManager.removeUpdates(it) }
    }
}
