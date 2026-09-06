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
import kotlin.math.abs

class MySafeAgentService : Service() {
    companion object {
        private const val TAG = "MySafe-Agent"
        const val SMS_RECEIVED = "com.mysafe.mysafe.SMS_RECEIVED"
        const val ACTION_SEND_COMMAND = "com.mysafe.mysafe.SEND_COMMAND"
        private const val CHANNEL_ID = "MySafeServiceChannel"
        
        // 🎯 COMME GOOGLE MAPS : Accepter jusqu'à 5m, viser 2-3m
        private const val TARGET_ACCURACY = 3f    // ✅ Objectif : 3m max
        private const val MAX_ACCEPTED_ACCURACY = 8f // ✅ Accepter max 8m si 3m impossible
        private const val MAX_WAIT_TIME = 20000L   // ⏳ Attendre 20s max pour bonne position
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
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
        
        // 🎯 EXACTEMENT COMME GOOGLE MAPS : PRIORITÉ MAXIMALE
        locationRequest = LocationRequest.create().apply {
            interval = 3000           // Mise à jour toutes les 3s
            fastestInterval = 1000     // Jusqu'à 1 mise à jour par seconde
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 1f  // Mise à jour si déplacement de 1m
            maxWaitTime = 5000
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { checkLocation(it) }
            }
        }
        
        startGpsNativeListener()
    }

    // 🎯 ÉCOUTER ÉGALEMENT LE GPS NATIF (comme Google Maps)
    private fun startGpsNativeListener() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        gpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                Log.d(TAG, "🛰️ GPS Natif: ${loc.accuracy.toInt()}m")
                checkLocation(loc)
            }
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000,
            0f,
            gpsListener!!
        )
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            1000,
            0f,
            gpsListener!!
        )
    }

    // 🎯 ÉVALUER CHAQUE POSITION REÇUE
    private fun checkLocation(loc: Location) {
        val now = System.currentTimeMillis()
        
        // Ignorer les positions trop vieilles (>1min)
        if (now - loc.time > 60000) {
            Log.d(TAG, "⏭️ Position trop vieille ignorée")
            return
        }
        
        // Mettre à jour la meilleure position
        if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
            bestLocation = loc
            Log.d(TAG, "✅ NOUVELLE MEILLEURE POSITION: ${loc.accuracy.toInt()}m (${loc.provider})")
        }
        
        // ✅ Si précision excellente → RÉPONDRE TOUT DE SUITE
        if (bestLocation!!.accuracy <= TARGET_ACCURACY) {
            Log.d(TAG, "🎯 PRÉCISION OBJECTIF ATTEINTE: ${bestLocation!!.accuracy.toInt()}m ≤ ${TARGET_ACCURACY}m")
            if (!isTracking) sendBestLocation() // Pour demande ponctuelle
            return
        }
        
        // ⏳ Si temps écoulé → RÉPONDRE avec la meilleure obtenue
        if (locationStartTime > 0 && now - locationStartTime > MAX_WAIT_TIME) {
            Log.d(TAG, "⏳ Temps écoulé — meilleure précision obtenue: ${bestLocation!!.accuracy.toInt()}m")
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
                Log.d(TAG, "📍 Demande de position — Recherche précision ${TARGET_ACCURACY}m...")
                targetNumber = senderNumber
                bestLocation = null
                locationStartTime = System.currentTimeMillis()
                requestFreshLocation()
            }
            message == "!!DEMARRER" -> {
                Log.d(TAG, "🔔 Suivi continu démarré — Précision ${TARGET_ACCURACY}m cible")
                targetNumber = senderNumber
                isTracking = true
                startLocationUpdates()
            }
            message == "!!STOP" -> {
                Log.d(TAG, "🛑 Suivi arrêté")
                isTracking = false
                stopLocationUpdates()
            }
            message.startsWith("!!") && message.contains(",") -> {
                Log.d(TAG, "📩 Réponse de position reçue : $message")
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
        
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
    }

    private fun sendBestLocation() {
        val loc = bestLocation ?: run {
            Log.d(TAG, "❌ Aucune position trouvée")
            return
        }
        
        val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()},${loc.accuracy.toInt()}m"
        Log.d(TAG, "📤 ENVOI POSITION: $response")
        sendSms(targetNumber, response)
        
        // Nettoyer
        locationStartTime = 0
        fusedLocationClient.removeLocationUpdates(locationCallback!!)
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
                        sendSms(targetNumber, response)
                    }
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
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
            Log.d(TAG, "✅ SMS ENVOYÉ à $to : $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ÉCHEC ENVOI SMS à $to : ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe GPS")
            .setContentText("Recherche précision ${TARGET_ACCURACY}m...")
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
                description = "Service de localisation — Précision maximale"
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
