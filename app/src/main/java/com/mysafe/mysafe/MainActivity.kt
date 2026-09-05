package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var etTargetNumber: EditText
    private lateinit var tvPositions: TextView
    private lateinit var btnPosition: Button
    private lateinit var btnDemarrer: Button
    private lateinit var btnStop: Button
    private lateinit var btnFloatingMap: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnTestDirect: Button

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var userMarker: Marker? = null
    private var myPhoneNumber: String = ""
    private val DEFAULT_ANGERS = GeoPoint(47.4728, -0.5416)

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("sms_message")?.let { runOnUiThread { handleIncomingMessage(it) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPerms()
        setupMap()
        setupFusedLocation()
        registerReceiver(smsReceiver, IntentFilter(MySafeAgentService.SMS_RECEIVED), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0)
        addLog("✅ MySafe prête — Google Location actif !")
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        etTargetNumber = findViewById(R.id.etTargetNumber)
        tvPositions = findViewById(R.id.tvPositions)
        btnPosition = findViewById(R.id.btnPosition)
        btnDemarrer = findViewById(R.id.btnDemarrer)
        btnStop = findViewById(R.id.btnStop)
        btnFloatingMap = findViewById(R.id.btnFloatingMap)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        btnTestDirect = findViewById(R.id.btnTestDirect)
        btnPosition.setOnClickListener { sendCommand("!!POSITION") }
        btnDemarrer.setOnClickListener { sendCommand("!!DEMARRER") }
        btnStop.setOnClickListener { sendCommand("!!STOP") }
        btnFloatingMap.setOnClickListener { openFloatingMap() }
        btnClearHistory.setOnClickListener { clearMap() }
        btnTestDirect.setOnClickListener { getInstantPosition() }
    }

    private fun setupMap() {
        Configuration.getInstance().apply {
            osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
            osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")
            userAgentValue = "MySafe-App"
        }
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(DEFAULT_ANGERS)
        }
    }

    private fun setupFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 5f
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleNewLocation(it) }
            }
        }
    }

    private fun getInstantPosition() {
        addLog("🧪 Récupération position...")
        if (!hasLocationPermission()) {
            Toast.makeText(this, "❌ Autorise la localisation", Toast.LENGTH_LONG).show()
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && isLocationValid(loc)) handleNewLocation(loc) else requestFreshLocation()
        }.addOnFailureListener { requestFreshLocation() }
    }

    private fun requestFreshLocation() {
        if (!hasLocationPermission()) return
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val req = LocationRequest.create().apply {
            numUpdates = 1
            expirationTime = 15000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback!!, mainLooper)
        Toast.makeText(this, "⏳ Recherche...", Toast.LENGTH_SHORT).show()
    }

    private fun isLocationValid(loc: Location): Boolean {
        val zero = loc.latitude == 0.0 && loc.longitude == 0.0
        val age = System.currentTimeMillis() - loc.time
        return !zero && age <= 86400000
    }

    private fun handleNewLocation(loc: Location) {
        addLog("📍 ${loc.latitude}, ${loc.longitude} — ${loc.accuracy.toInt()}m")
        val point = GeoPoint(loc.latitude, loc.longitude)
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = Marker(mapView).apply {
            position = point
            title = "📍 Tu es ici !"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
        }
        mapView.overlays.add(userMarker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        Toast.makeText(this, "📍 Position trouvée !", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun normalizeNumber(s: String) = s.replace("\\s".toRegex(), "").replace("-", "").let {
        if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it
    }

    private fun isMyNumber(t: String): Boolean {
        val my = normalizeNumber(myPhoneNumber)
        val ot = normalizeNumber(t)
        return my.isNotEmpty() && (ot == my || ot == my.replace("+33", "0") || my == ot.replace("+33", "0"))
    }

    private fun sendCommand(cmd: String) {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "⚠️ Entre un numéro", Toast.LENGTH_SHORT).show(); return }
        myPhoneNumber = num
        addLog("📤 $cmd → $num")
        if (isMyNumber(num)) { handleLocalCommand(cmd); return }
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_PROCESS_COMMAND
            putExtra("sender_number", num)
            putExtra("command", cmd)
        }
        ContextCompat.startForegroundService(this, svc)
    }

    private fun handleLocalCommand(cmd: String) {
        when (cmd.uppercase()) {
            "!!POSITION" -> getInstantPosition()
            "!!DEMARRER" -> {
                startForegroundService(Intent(this, MySafeAgentService::class.java))
                Toast.makeText(this, "✅ SUIVI DÉMARRÉ 🟢", Toast.LENGTH_LONG).show()
            }
            "!!STOP" -> {
                stopService(Intent(this, MySafeAgentService::class.java))
                Toast.makeText(this, "✅ SUIVI ARRÊTÉ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIncomingMessage(msg: String) {
        addLog("📩 $msg")
        if (msg.startsWith("!!") && msg.contains(",")) {
            val p = msg.removePrefix("!!").split(",")
            val lat = p[0].toDoubleOrNull()
            val lon = p[1].toDoubleOrNull()
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                addPositionFromRemote(lat, lon, p.getOrNull(2) ?: "?")
            }
        }
    }

    private fun addPositionFromRemote(lat: Double, lon: Double, alt: String) {
        val point = GeoPoint(lat, lon)
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = Marker(mapView).apply {
            position = point
            title = "📍 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(userMarker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    private fun addLog(text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvPositions.text = "[$t] $text\n\n${tvPositions.text}"
    }

    private fun clearMap() {
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = null
        mapView.overlays.clear()
        mapView.invalidate()
        tvPositions.text = ""
        addLog("🗑️ Carte effacée")
    }

    private fun openFloatingMap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, FloatingMapWindow::class.java))
    }

    private fun checkPerms() {
        val missing = PERMS.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
