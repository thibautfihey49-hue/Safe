package com.mysafe.mysafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
        private const val TAG = "MySafe"
        private const val NOTIF_ID = 12345
        private const val CHANNEL_ID = "MySafeGPS"
    }

    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var locationListener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ SERVICE MySafe CRÉÉ")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createNotificationChannel()
        startForegroundSafety()
        startGpsListening()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "MySafe GPS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suivi GPS en cours"
                lightColor = Color.GREEN
                enableLights(true)
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
            .setContentTitle("🟢 MySafe — GPS ACTIF")
            .setContentText("Localisation en temps réel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startGpsListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        locationListener = object : LocationListener {
            override fun onLocationChanged(l: android.location.Location) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener!!)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener!!)
            Log.d(TAG, "🟢 GPS ACTIF — pastille visible !")
        } catch (e: Exception) { Log.e(TAG, "❌ GPS: ${e.message}") }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.let { locationManager.removeUpdates(it) }
    }
}
