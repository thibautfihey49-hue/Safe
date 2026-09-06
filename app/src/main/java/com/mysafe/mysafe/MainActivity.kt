package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
    private lateinit var btnMyPosition: Button
    private lateinit var btnGetHisPosition: Button
    private lateinit var btnDemarrer: Button
    private lateinit var btnStop: Button
    private lateinit var btnFloatingMap: Button
    private lateinit var btnClearHistory: Button

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var userMarker: Marker? = null
    private var remoteMarker: Marker? = null
    private var targetPhoneNumber: String = ""
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
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.RECEIVE_BOOT_COMPLETED
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val smsResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("sms_message")
            val sender = intent?.getStringExtra("sender_number")
            Log.d("MySafe-UI", "📥 Reçu de [$sender] : [$msg]")
            if (msg != null) runOnUiThread { handleIncomingMessage(msg, sender) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPerms()
        setupMap()
        setupFusedLocation()
        
        val intentFilter = IntentFilter("com.mysafe.mysafe.SMS_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsResponseReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsResponseReceiver, intentFilter)
        }
        
        addLog("✅ MySafe PRÊT — Précision GPS maximale activée")
        addLog("📍 🟢 MOI | 🔴 L'AUTRE")
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        etTargetNumber = findViewById(R.id.etTargetNumber)
        tvPositions = findViewById(R.id.tvPositions)
        btnMyPosition = findViewById(R.id.btnMyPosition)
        btnGetHisPosition = findViewById(R.id.btnGetHisPosition)
        btnDemarrer = findViewById(R.id.btnDemarrer)
        btnStop = findViewById(R.id.btnStop)
        btnFloatingMap = findViewById(R.id.btnFloatingMap)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        btnMyPosition.setOnClickListener { getMyPosition() }
        btnGetHisPosition.setOnClickListener { askHisPosition() }
        btnDemarrer.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnFloatingMap.setOnClickListener { openFloatingMap() }
        btnClearHistory.setOnClickListener { clearMap() }
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
            controller.setZoom(17.0)
            controller.setCenter(DEFAULT_ANGERS)
        }
    }

    private fun setupFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 2f
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { showMyPosition(it) }
            }
        }
    }

    private fun getMyPosition() {
        addLog("📍 === MA POSITION ===")
        if (!hasLocationPermission()) {
            Toast.makeText(this, "❌ Autorise la position GPS précise !", Toast.LENGTH_LONG).show()
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && isLocationValid(loc)) {
                showMyPosition(loc)
            } else {
                requestFreshLocation()
            }
        }
    }

    private fun askHisPosition() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "⚠️ Entre le numéro de l'autre !", Toast.LENGTH_SHORT).show()
            return
        }
        targetPhoneNumber = normalizeNumber(num)
        addLog("📤 === DEMANDE SA POSITION ===")
        addLog("📩 Envoi à : $targetPhoneNumber")
        
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_SEND_COMMAND
            putExtra("target_number", targetPhoneNumber)
            putExtra("command", "!!POSITION")
        }
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, "📩 Demande envoyée ! Attends la réponse...", Toast.LENGTH_LONG).show()
    }

    private fun startTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "⚠️ Entre un numéro", Toast.LENGTH_SHORT).show(); return }
        targetPhoneNumber = normalizeNumber(num)
        addLog("📤 === SUIVI CONTINU ===")
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_SEND_COMMAND
            putExtra("target_number", targetPhoneNumber)
            putExtra("command", "!!DEMARRER")
        }
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, "✅ Suivi démarré !", Toast.LENGTH_LONG).show()
    }

    private fun stopTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) return
        targetPhoneNumber = normalizeNumber(num)
        addLog("📤 === ARRÊT SUIVI ===")
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_SEND_COMMAND
            putExtra("target_number", targetPhoneNumber)
            putExtra("command", "!!STOP")
        }
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, "✅ Suivi arrêté !", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "⏳ Recherche position précise...", Toast.LENGTH_SHORT).show()
    }

    private fun isLocationValid(loc: Location): Boolean {
        val zero = loc.latitude == 0.0 && loc.longitude == 0.0
        val age = System.currentTimeMillis() - loc.time
        return !zero && age <= 86400000
    }

    private fun showMyPosition(loc: Location) {
        addLog("📍 MOI : ${loc.latitude}, ${loc.longitude} — ✅ Précision: ${loc.accuracy.toInt()}m")
        val point = GeoPoint(loc.latitude, loc.longitude)
        
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = Marker(mapView).apply {
            position = point
            title = "🟢 MOI — ${loc.accuracy.toInt()}m"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
        }
        mapView.overlays.add(userMarker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    private fun handleIncomingMessage(msg: String, sender: String?) {
        addLog("📩 === MESSAGE REÇU ===")
        addLog("📩 De: $sender | Contenu: $msg")
        
        // ✅ IGNORER LES COMMANDES — ce ne sont pas des réponses de position !
        when (msg) {
            "!!POSITION", "!!DEMARRER", "!!STOP" -> {
                addLog("ℹ️ Commande détectée — ignorée (ce n'est pas une réponse de position)")
                return
            }
        }
        
        // ✅ VÉRIFIER LE FORMAT DE RÉPONSE : !!lat,lon,alt,précision
        if (!msg.startsWith("!!")) {
            addLog("ℹ️ Message normal — ignoré")
            return
        }
        
        val clean = msg.removePrefix("!!")
        val parts = clean.split(",")
        
        // ✅ VÉRIFIER LE NOMBRE DE CHAMPS : au moins lat + lon
        if (parts.size < 2) {
            addLog("⚠️ Format invalide — pas assez de valeurs séparées par virgule")
            return
        }
        
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        val alt = parts.getOrNull(2) ?: "?"
        val accuracy = parts.getOrNull(3) ?: "?"
        
        if (lat == null || lon == null) {
            addLog("⚠️ Coordonnées invalides — lat/lon ne sont pas des nombres")
            return
        }
        
        if (lat == 0.0 && lon == 0.0) {
            addLog("⚠️ Position nulle ignorée")
            return
        }
        
        addLog("🔴 Lat=$lat Lon=$lon Alt=$alt ✅ Précision: $accuracy")
        showRemotePosition(lat, lon, alt, accuracy)
    }

    private fun showRemotePosition(lat: Double, lon: Double, alt: String, accuracy: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        addLog("✅ === AFFICHAGE SUR LA CARTE ===")
        
        val point = GeoPoint(lat, lon)
        
        remoteMarker?.let { 
            mapView.overlays.remove(it)
            addLog("🗑️ Ancien marqueur supprimé")
        }
        
        remoteMarker = Marker(mapView).apply {
            position = point
            title = "🔴 LUI — $time | ✅ $accuracy"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_compass)
        }
        
        remoteMarker?.let {
            mapView.overlays.add(it)
            addLog("✅ MARQUEUR ROUGE AJOUTÉ ! Précision: $accuracy")
        }
        
        mapView.controller.animateTo(point)
        mapView.invalidate()
        
        Toast.makeText(this, "🔴 Position affichée — Précision: $accuracy", Toast.LENGTH_LONG).show()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun normalizeNumber(s: String) = s.replace("\\s".toRegex(), "").replace("-", "").let {
        if (it.startsWith("+")) it else if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it
    }

    private fun addLog(text: String) {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvPositions.text = "[$t] $text\n\n${tvPositions.text}"
    }

    private fun clearMap() {
        userMarker?.let { mapView.overlays.remove(it) }
        remoteMarker?.let { mapView.overlays.remove(it) }
        userMarker = null
        remoteMarker = null
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
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsResponseReceiver)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
