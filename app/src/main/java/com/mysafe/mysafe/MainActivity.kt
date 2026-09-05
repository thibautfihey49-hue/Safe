package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("sms_message")?.let { msg ->
                runOnUiThread { handleIncomingMessage(msg) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPerms()
        setupMap()
        setupFusedLocation()

        val filter = IntentFilter(MySafeAgentService.SMS_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }

        addLog("✅ Application prête — avec Google Location Services !")
        addLog("📍 Carte centrée sur Angers")
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
                result.lastLocation?.let { loc ->
                    handleNewLocation(loc)
                }
            }
        }
    }

    private fun getInstantPosition() {
        addLog("🧪 === RÉCUPÉRATION POSITION (GOOGLE) ===")
        
        if (!hasLocationPermission()) {
            addLog("❌ Permission localisation manquante")
            Toast.makeText(this, "❌ Autorise la localisation d'abord", Toast.LENGTH_LONG).show()
            return
        }

        addLog("⏳ Récupération de la position...")
        
        // ✅ Méthode 1 : Dernière position connue (instantanée)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null && isLocationValid(location)) {
                    addLog("✅ Position trouvée (dernière connue) !")
                    handleNewLocation(location)
                } else {
                    addLog("⚠️ Position connue trop ancienne — demande mise à jour...")
                    requestLocationUpdate()
                }
            }
            .addOnFailureListener { e ->
                addLog("❌ Erreur : ${e.message}")
                Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ✅ Méthode 2 : Demander une nouvelle position (compatible toutes versions)
    private fun requestLocationUpdate() {
        if (!hasLocationPermission()) return
        
        // Arrêter toute mise à jour en cours
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        
        // Demander une seule mise à jour
        val request = locationRequest.copy()
        request.numUpdates = 1
        request.expirationTime = 15000
        
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, mainLooper)
        addLog("⏳ Demande de position en cours...")
        Toast.makeText(this, "⏳ Recherche position...", Toast.LENGTH_SHORT).show()
    }

    private fun isLocationValid(loc: Location): Boolean {
        val isZero = loc.latitude == 0.0 && loc.longitude == 0.0
        val age = System.currentTimeMillis() - loc.time
        val isTooOld = age > 24 * 60 * 60 * 1000
        return !isZero && !isTooOld
    }

    private fun handleNewLocation(loc: Location) {
        addLog("📍 Coordonnées : ${loc.latitude}, ${loc.longitude}")
        addLog("📊 Précision : ${loc.accuracy.toInt()}m | Source : ${loc.provider}")
        
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
        
        // Nettoyer les mises à jour ponctuelles
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        
        Toast.makeText(this, "📍 Position trouvée !", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun normalizeNumber(num: String): String {
        var n = num.replace("\\s".toRegex(), "").replace("-", "")
        if (n.startsWith("0") && n.length == 10) n = "+33" + n.substring(1)
        return n
    }

    private fun isMyNumber(target: String): Boolean {
        val my = normalizeNumber(myPhoneNumber)
        val t = normalizeNumber(target)
        return my.isNotEmpty() && (t == my || t == my.replace("+33", "0") || my == t.replace("+33", "0"))
    }

    private fun sendCommand(cmd: String) {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "⚠️ Entre un numéro d'abord !", Toast.LENGTH_SHORT).show()
            return
        }
        myPhoneNumber = num
        
        addLog("========================================")
        addLog("📤 COMMANDE : $cmd → $num")
        
        if (isMyNumber(num)) {
            addLog("✅ Mode local détecté — 0 SMS")
            handleLocalCommand(cmd)
            return
        }
        
        addLog("📡 Démarrage service...")
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_PROCESS_COMMAND
            putExtra("sender_number", num)
            putExtra("command", cmd)
        }
        try {
            ContextCompat.startForegroundService(this, svc)
            addLog("✅ Service démarré !")
            Toast.makeText(this, "✅ Service démarré", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("❌ ERREUR : ${e.message}")
        }
    }

    private fun handleLocalCommand(cmd: String) {
        when (cmd.uppercase()) {
            "!!POSITION" -> getInstantPosition()
            "!!DEMARRER" -> startLocalTracking()
            "!!STOP" -> stopLocalTracking()
        }
    }

    private fun startLocalTracking() {
        addLog("▶️ DÉMARRAGE SUIVI LOCAL...")
        val svc = Intent(this, MySafeAgentService::class.java)
        try {
            ContextCompat.startForegroundService(this, svc)
            addLog("✅ Service de suivi démarré !")
            Toast.makeText(this, "✅ SUIVI DÉMARRÉ", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("❌ ERREUR : ${e.message}")
        }
    }

    private fun stopLocalTracking() {
        addLog("⏹️ ARRÊT SUIVI...")
        stopService(Intent(this, MySafeAgentService::class.java))
        addLog("✅ Suivi arrêté")
        Toast.makeText(this, "✅ SUIVI ARRÊTÉ", Toast.LENGTH_SHORT).show()
    }

    private fun handleIncomingMessage(msg: String) {
        addLog("📩 Réponse reçue : $msg")
        when {
            msg.startsWith("!!OK-") -> Toast.makeText(this, "✅ $msg", Toast.LENGTH_SHORT).show()
            msg.startsWith("!!ERREUR") -> Toast.makeText(this, "⚠️ $msg", Toast.LENGTH_LONG).show()
            msg.startsWith("!!") && msg.contains(",") -> {
                val parts = msg.removePrefix("!!").split(",")
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                    addPositionFromRemote(lat, lon, if (parts.size > 2) parts[2] else "?")
                }
            }
        }
    }

    private fun addPositionFromRemote(lat: Double, lon: Double, alt: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        addLog("📍 Position distante : $lat, $lon — Alt: $alt m")
        
        val point = GeoPoint(lat, lon)
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = Marker(mapView).apply {
            position = point
            title = "📍 $time"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(userMarker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvPositions.text = "[$time] $text\n\n${tvPositions.text}"
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
        val missing = PERMS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            addLog("⚠️ Demande de permissions...")
            ActivityCompat.requestPermissions(this, missing, 1001)
        } else {
            addLog("✅ Toutes permissions accordées")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
