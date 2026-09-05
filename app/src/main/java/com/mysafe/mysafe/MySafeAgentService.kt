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
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var targetNumber: String = ""
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 30000
            fastestInterval = 10000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (isTracking && targetNumber.isNotEmpty()) {
                        sendSms(targetNumber, "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}")
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

    // ✅ TRAITER LES COMMANDES REÇUES — AVEC L'EXPÉDITEUR !
    private fun handleIncomingCommand(message: String, senderNumber: String) {
        Log.d(TAG, "📩 Commande reçue de [$senderNumber] : [$message]")
        
        when {
            message == "!!POSITION" -> {
                Log.d(TAG, "📍 Demande de position — je réponds à $senderNumber !")
                getCurrentLocationAndReply(senderNumber)
            }
            message == "!!DEMARRER" -> {
                Log.d(TAG, "🔔 Suivi continu démarré pour $senderNumber")
                targetNumber = senderNumber
                isTracking = true
                startLocationUpdates()
            }
            message == "!!STOP" -> {
                Log.d(TAG, "🛑 Suivi arrêté")
                isTracking = false
                stopLocationUpdates()
            }
            // ✅ Si c'est une RÉPONSE de position → la transmettre à l'UI
            message.startsWith("!!") && message.contains(",") -> {
                Log.d(TAG, "📩 Réponse de position reçue de $senderNumber : $message")
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

    private fun getCurrentLocationAndReply(replyToNumber: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "❌ Pas de permission GPS")
            return
        }
        
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
                Log.d(TAG, "✅ Position trouvée → Réponse à $replyToNumber : $response")
                sendSms(replyToNumber, response)
            } else {
                Log.d(TAG, "⏳ Position trop vieille — demande fraîche...")
                requestFreshLocationAndReply(replyToNumber)
            }
        }
    }

    private fun requestFreshLocationAndReply(replyToNumber: String) {
        val req = LocationRequest.create().apply {
            numUpdates = 1
            expirationTime = 15000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
                    Log.d(TAG, "✅ Position fraîche → Réponse à $replyToNumber : $response")
                    sendSms(replyToNumber, response)
                    fusedLocationClient.removeLocationUpdates(this)
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
            fusedLocationClient.requestLocationUpdates(locationRequest, it, mainLooper)
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
