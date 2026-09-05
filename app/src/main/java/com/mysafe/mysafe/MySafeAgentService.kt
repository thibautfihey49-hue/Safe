package com.mysafe.mysafe
import android.app.*
import android.content.Context
import android.location.*
import android.os.*
import android.telephony.SmsManager
import android.util.Log

class MySafeAgentService : Service() {
    companion object {
        const val ACTION_PROCESS_COMMAND = "com.mysafe.mysafe.PROCESS_CMD"
        private const val CHANNEL_ID = "MySafeService"
        private const val DISTANCE_THRESHOLD = 10f
        private const val TIME_THRESHOLD = 90000L
    }
    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var currentSender: String? = null
    private var isTracking = false
    private var lastLocation: Location? = null
    private var lastSendTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = processNewLocation(loc)
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createNotificationChannel()
        startForeground(0x7777, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.takeIf { it.action == ACTION_PROCESS_COMMAND }?.let {
            handleCommand(it.getStringExtra("command")!!, it.getStringExtra("sender_number")!!)
        }
        return START_STICKY
    }

    private fun handleCommand(cmd: String, sender: String) {
        when(cmd.uppercase()) {
            "!!POSITION" -> sendSingle(sender)
            "!!DEMARRER" -> startTracking(sender)
            "!!STOP" -> stopTracking(sender)
        }
    }

    private fun sendSingle(sender: String) {
        getLastLocation()?.let { sendLoc(sender, it) } ?: sendSMS(sender, "!!POSITION_INCONNUE")
    }

    private fun startTracking(sender: String) {
        if (isTracking) { sendSMS(sender, "!!OK-SUIVI"); return }
        isTracking = true
        sendSMS(sender, "!!OK-SUIVI")
        getLastLocation()?.let { sendLoc(sender, it) }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, TIME_THRESHOLD, DISTANCE_THRESHOLD, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, TIME_THRESHOLD, DISTANCE_THRESHOLD, locationListener)
        } catch(e: SecurityException) {}
    }

    private fun stopTracking(sender: String) {
        isTracking = false
        locationManager.removeUpdates(locationListener)
        sendSMS(sender, "!!OK-STOP")
        lastLocation = null
    }

    private fun processNewLocation(loc: Location) {
        if (!isTracking || currentSender == null) return
        val now = System.currentTimeMillis()
        val send = when {
            lastLocation == null -> true
            loc.distanceTo(lastLocation!!) >= DISTANCE_THRESHOLD -> true
            now - lastSendTime >= TIME_THRESHOLD -> true
            else -> false
        }
        if (!send) return
        sendLoc(currentSender!!, loc)
        lastLocation = loc
        lastSendTime = now
    }

    private fun sendLoc(to: String, loc: Location) {
        sendSMS(to, "!!${String.format("%.6f",loc.latitude)},${String.format("%.6f",loc.longitude)},${String.format("%.1f",loc.altitude)}")
    }

    private fun sendSMS(dest: String, msg: String) {
        try { smsManager.sendDataMessage(dest, null, SmsManager.ENCODING_16BIT, msg.toByteArray(Charsets.UTF_8), null, null) }
        catch(e: Exception) { Log.e("MySafe", "SMS failed", e) }
    }

    private fun getLastLocation(): Location? {
        var best: Location? = null
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach {
            try { locationManager.getLastKnownLocation(it)?.let { loc ->
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
            }} catch(e: SecurityException) {}
        }
        return best
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CHANNEL_ID, "MySafe", NotificationManager.IMPORTANCE_LOW)
            c.setSound(null,null); c.enableVibration(false); c.enableLights(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    override fun onBind(i: Intent?) = null
    override fun onDestroy() { super.onDestroy(); isTracking = false; locationManager.removeUpdates(locationListener) }
}
