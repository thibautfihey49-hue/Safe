package com.mysafe.mysafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
        private const val NOTIF_ID = 0x7777
        private const val CHANNEL_ID = "MySafeService"
        private const val DISTANCE_THRESHOLD = 10f
        private const val TIME_THRESHOLD = 90000L
        private const val SMS_DATA_ENCODING: Short = 0x04
    }

    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var currentSender: String? = null
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
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createSilentNotificationChannel()
        
        // ✅ Vérifier les permissions AVANT de passer en foreground
        if (hasLocationPermission()) {
            startForeground(NOTIF_ID, buildSilentNotification())
        } else {
            Log.w(TAG, "Permission localisation manquante — service lancé sans foreground")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_PROCESS_COMMAND) {
                val cmd = it.getStringExtra("command") ?: return@let
                val sender = it.getStringExtra("sender_number") ?: return@let
                currentSender = sender
                handleCommand(cmd, sender)
            }
        }
        return START_STICKY
    }

    private fun handleCommand(command: String, sender: String) {
        if (!hasLocationPermission()) {
            sendDataSMS(sender, "!!ERREUR-AUTORISATION: Accorde la localisation d'abord")
            broadcastMessage("!!ERREUR-AUTORISATION: Accorde la localisation d'abord")
            return
        }

        when (command.uppercase()) {
            "!!POSITION" -> sendSinglePosition(sender)
            "!!DEMARRER" -> startContinuousTracking(sender)
            "!!STOP" -> stopContinuousTracking(sender)
        }
    }

    private fun sendSinglePosition(sender: String) {
        try {
            val loc = getLastKnownLocation() ?: run {
                sendDataSMS(sender, "!!POSITION_INCONNUE")
                return
            }
            sendLocationResponse(sender, loc)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissions GPS manquantes", e)
        }
    }

    private fun startContinuousTracking(sender: String) {
        if (isTracking) {
            sendDataSMS(sender, "!!OK-SUIVI")
            broadcastMessage("!!OK-SUIVI")
            return
        }
        isTracking = true
        sendDataSMS(sender, "!!OK-SUIVI")
        broadcastMessage("!!OK-SUIVI")

        getLastKnownLocation()?.let { 
            sendLocationResponse(sender, it)
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                TIME_THRESHOLD,
                DISTANCE_THRESHOLD,
                locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                TIME_THRESHOLD,
                DISTANCE_THRESHOLD,
                locationListener
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Impossible de démarrer le GPS", e)
        }
    }

    private fun stopContinuousTracking(sender: String) {
        isTracking = false
        locationManager.removeUpdates(locationListener)
        sendDataSMS(sender, "!!OK-STOP")
        broadcastMessage("!!OK-STOP")
        lastLocation = null
    }

    private fun processNewLocation(location: Location) {
        if (!isTracking || currentSender == null) return

        val now = System.currentTimeMillis()
        val last = lastLocation

        val shouldSend = when {
            last == null -> true
            location.distanceTo(last) >= DISTANCE_THRESHOLD -> true
            now - lastSendTime >= TIME_THRESHOLD -> true
            else -> false
        }

        if (!shouldSend) return

        sendLocationResponse(currentSender!!, location)
        lastLocation = location
        lastSendTime = now
    }

    private fun sendLocationResponse(to: String, loc: Location) {
        val lat = String.format("%.6f", loc.latitude)
        val lon = String.format("%.6f", loc.longitude)
        val alt = String.format("%.1f", loc.altitude)
        val message = "!!$lat,$lon,$alt"
        sendDataSMS(to, message)
        broadcastMessage(message)
    }

    private fun broadcastMessage(msg: String) {
        val intent = Intent(SMS_RECEIVED).putExtra("sms_message", msg)
        sendBroadcast(intent)
    }

    private fun sendDataSMS(dest: String, message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_16)
            smsManager.sendDataMessage(dest, null, SMS_DATA_ENCODING, data, null, null)
            Log.d(TAG, "SMS envoyé à $dest: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Échec envoi SMS", e)
        }
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
            Log.e(TAG, "Accès GPS refusé", e)
        }
        return best
    }

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "MySafe Service", NotificationManager.IMPORTANCE_LOW)
            chan.setSound(null, null)
            chan.enableVibration(false)
            chan.enableLights(false)
            chan.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildSilentNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe")
            .setContentText("Service actif")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setVibrate(longArrayOf(0))
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        locationManager.removeUpdates(locationListener)
    }
}
