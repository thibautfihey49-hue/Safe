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
                        sendLocationResponse(loc, targetNumber)
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
                handleOutgoingCommand(target, command)
            }
            // ✅ RÉPONDRE AUX COMMANDES REÇUES PAR SMS
            SMS_RECEIVED -> {
                val message = intent.getStringExtra("sms_message") ?: return START_NOT_STICKY
                handleIncomingCommand(message)
            }
        }
        return START_STICKY
    }

    // ✅ TRAITER LES COMMANDES REÇUES PAR SMS DE L'AUTRE TÉLÉPHONE
    private fun handleIncomingCommand(message: String) {
        Log.d(TAG, "📩 Commande reçue par SMS: $message")
        
        when {
            message == "!!POSITION" -> {
                Log.d(TAG, "📍 Demande de position reçue — Envoi de ma position !")
                getCurrentLocationAndReply()
            }
            message == "!!DEMARRER" -> {
                Log.d(TAG, "🔔 Suivi continu démarré !")
                isTracking = true
                startLocationUpdates()
            }
            message == "!!STOP" -> {
                Log.d(TAG, "🛑 Suivi arrêté !")
                isTracking = false
                stopLocationUpdates()
            }
        }
    }

    private fun handleOutgoingCommand(target: String, command: String) {
        targetNumber = target
        Log.d(TAG, "📤 Envoi commande '$command' à $target")
        
        when (command) {
            "!!POSITION" -> sendSms(target, "!!POSITION")
            "!!DEMARRER" -> {
                isTracking = true
                sendSms(target, "!!DEMARRER")
            }
            "!!STOP" -> {
                isTracking = false
                sendSms(target, "!!STOP")
            }
        }
    }

    private fun getCurrentLocationAndReply() {
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
                Log.d(TAG, "✅ Position trouvée: ${loc.latitude}, ${loc.longitude}")
                val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
                sendSmsReply(response)  // ✅ RÉPONDRE À L'EXPÉDITEUR !
            } else {
                Log.d(TAG, "⏳ Position trop vieille ou nulle — demande de position fraîche")
                requestFreshLocationAndReply()
            }
        }
    }

    private fun requestFreshLocationAndReply() {
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
                    Log.d(TAG, "✅ Position fraîche obtenue: $response")
                    sendSmsReply(response)
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
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    private fun sendLocationResponse(loc: Location, toNumber: String) {
        val response = "!!${loc.latitude},${loc.longitude},${loc.altitude.toInt()}"
        Log.d(TAG, "📤 Envoi position à $toNumber: $response")
        sendSms(toNumber, response)
    }

    private fun sendSmsReply(message: String) {
        // ⚡ RÉPONDRE AU NUMÉRO QUI A DEMANDÉ LA POSITION
        // On a besoin de stocker l'expéditeur quand on reçoit le SMS
        // Pour l'instant → on utilise le numéro cible connu
        if (targetNumber.isNotEmpty()) {
            sendSms(targetNumber, message)
        } else {
            Log.d(TAG, "⚠️ Numéro cible inconnu — impossible de répondre")
        }
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
            Log.d(TAG, "✅ SMS envoyé à $to: $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec envoi SMS: ${e.message}")
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
