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
        private const val MIN_ACCURACY = 30f // ✅ Moins de 30 mètres = valide
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequestHighAccuracy: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var targetNumber: String = ""
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // ✅ PRÉCISION MAXIMALE !
        locationRequestHighAccuracy = LocationRequest.create().apply {
            interval = 5000        // 5 secondes
            fastestInterval = 2000 // 2 secondes
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 2f // Bouger de 2m seulement = mise à jour
            maxWaitTime = 10000
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (isTracking && targetNumber.isNotEmpty() && loc.accuracy <= MIN_ACCURACY) {
                        sendSms(targetNumber, "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()},${loc.accuracy.toInt()}m")
                    }
                }
            }
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
                Log.d(TAG, "📍 Demande de position — je cherche la MEILLEURE précision !")
                getBestLocationAndReply(senderNumber)
            }
            message == "!!DEMARRER" -> {
                Log.d(TAG, "🔔 Suivi continu démarré — précision MAX")
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

    // ✅ TROUVER LA MEILLEURE POSITION PRÉCISE
    private fun getBestLocationAndReply(replyToNumber: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "❌ Autorise la position GPS précise !")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // ✅ Vérifier si GPS est activé
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d(TAG, "⚠️ GPS éteint — activer le GPS pour précision maximale")
        }

        // ✅ D'abord essayer la dernière position connue
        val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        var bestLoc: Location? = null
        
        if (gpsLoc != null && gpsLoc.accuracy <= MIN_ACCURACY) bestLoc = gpsLoc
        if (netLoc != null && (bestLoc == null || netLoc.accuracy < bestLoc.accuracy)) bestLoc = netLoc
        
        if (bestLoc != null) {
            val response = "!!${bestLoc.latitude},${bestLoc.longitude},${bestLoc.altitude.toInt()},${bestLoc.accuracy.toInt()}m"
            Log.d(TAG, "✅ Position trouvée — Précision: ${bestLoc.accuracy.toInt()}m")
            sendSms(replyToNumber, response)
            return
        }
        
        // ✅ Sinon demander une position FRAÎCHE et PRÉCISE
        requestFreshAccurateLocation(replyToNumber)
    }

    private fun requestFreshAccurateLocation(replyToNumber: String) {
        val req = LocationRequest.create().apply {
            numUpdates = 3 // ✅ Prendre 3 mesures et garder la meilleure
            expirationTime = 20000 // 20 secondes max
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 1f
        }
        
        var bestLocation: Location? = null
        var updateCount = 0
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                updateCount++
                result.lastLocation?.let { loc ->
                    Log.d(TAG, "📍 Position $updateCount: précision=${loc.accuracy.toInt()}m")
                    if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                        bestLocation = loc
                    }
                    // ✅ Arrêter si précision suffisante ou 3 essais
                    if (bestLocation!!.accuracy <= MIN_ACCURACY || updateCount >= 3) {
                        val bLoc = bestLocation!!
                        val response = "!!${bLoc.latitude},${bLoc.longitude},${bLoc.altitude.toInt()},${bLoc.accuracy.toInt()}m"
                        Log.d(TAG, "✅ MEILLEURE POSITION — Précision: ${bLoc.accuracy.toInt()}m")
                        sendSms(replyToNumber, response)
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                }
            }
        }, mainLooper)
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        locationCallback?.let {
            fusedLocationClient.requestLocationUpdates(locationRequestHighAccuracy, it, mainLooper)
        }
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
            .setContentText("Service de localisation actif")
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
                description = "Service de localisation en arrière-plan"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }
}
