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
        private const val NOTIF_ID = 0x7777
        private const val CHANNEL_ID = "MySafeService"
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
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createSilentNotificationChannel()
        if (hasLocationPermission()) {
            startForeground(NOTIF_ID, buildSilentNotification())
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizeNumber(num: String): String {
        var n = num.replace("\\s".toRegex(), "").replace("-", "")
        if (n.startsWith("0") && n.length == 10) n = "+33" + n.substring(1)
        return n
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
            Log.d(TAG, "📤 SMS à $target : $message")
        } catch (e: Exception) {
            Log.e(TAG, "Échec SMS", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        } catch (e: SecurityException) {}
    }

    private fun stopTracking(target: String) {
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
        } catch (e: SecurityException) {}
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setVibrate(longArrayOf(0))
        return builder.build()
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        locationManager.removeUpdates(locationListener)
    }
}
