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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var etTargetNumber: EditText
    private lateinit var tvPositions: TextView
    private lateinit var btnPosition: Button
    private lateinit var btnDemarrer: Button
    private lateinit var btnStop: Button
    private lateinit var btnFloatingMap: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnTestDirect: Button

    private lateinit var locationManager: LocationManager
    private var myPhoneNumber: String = ""

    // ✅ Ajout POST_NOTIFICATIONS pour Android 13+
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
        setupGps()

        val filter = IntentFilter(MySafeAgentService.SMS_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }

        addLog("✅ Prête — clique DÉMARRER pour lancer le suivi")
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
        btnTestDirect.setOnClickListener { testDirectPosition() }
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
            controller.setCenter(GeoPoint(47.4728, -0.5416))
        }
    }

    private fun setupGps() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
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

    private fun testDirectPosition() {
        addLog("🧪 Récupération position GPS...")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            addLog("❌ Permission GPS manquante !")
            return
        }
        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            addPosition(loc.latitude, loc.longitude, String.format("%.1f", loc.altitude))
            addLog("✅ Position trouvée !")
        } else {
            addLog("⚠️ Position inconnue — active le GPS et attends")
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null)
            Toast.makeText(this, "⏳ Attente GPS...", Toast.LENGTH_LONG).show()
        }
    }

    override fun onLocationChanged(location: Location) {
        runOnUiThread {
            addPosition(location.latitude, location.longitude, String.format("%.1f", location.altitude))
            addLog("✅ Position GPS reçue !")
        }
    }

    private fun sendCommand(cmd: String) {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "⚠️ Entrez un numéro", Toast.LENGTH_SHORT).show()
            return
        }
        myPhoneNumber = num
        
        if (isMyNumber(num)) {
            addLog("📡 Mode local — 0 SMS ✅")
            handleLocalCommand(cmd)
            return
        }
        
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_PROCESS_COMMAND
            putExtra("sender_number", num)
            putExtra("command", cmd)
        }
        ContextCompat.startForegroundService(this, svc)
        addLog("📤 Commande envoyée à $num : $cmd")
        Toast.makeText(this, "✅ Service démarré — vérifie la notification 🔔", Toast.LENGTH_LONG).show()
    }

    private fun handleLocalCommand(cmd: String) {
        when (cmd.uppercase()) {
            "!!POSITION" -> getLocalPosition()
            "!!DEMARRER" -> startLocalTracking()
            "!!STOP" -> stopLocalTracking()
        }
    }

    private fun getLocalPosition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            addLog("❌ Permission GPS manquante")
            return
        }
        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            val msg = "!!${String.format("%.6f", loc.latitude)},${String.format("%.6f", loc.longitude)},${String.format("%.1f", loc.altitude)}"
            handleIncomingMessage(msg)
        } else {
            addLog("⚠️ Position inconnue")
        }
    }

    private fun startLocalTracking() {
        addLog("✅ Suivi démarré (mode local)")
        Toast.makeText(this, "✅ Suivi démarré — notification visible 🔔", Toast.LENGTH_LONG).show()
        val svc = Intent(this, MySafeAgentService::class.java)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun stopLocalTracking() {
        addLog("✅ Suivi arrêté")
        Toast.makeText(this, "✅ Suivi arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun handleIncomingMessage(msg: String) {
        addLog("📩 Réponse : $msg")
        when {
            msg.startsWith("!!OK-") -> Toast.makeText(this, "✅ $msg", Toast.LENGTH_SHORT).show()
            msg.startsWith("!!ERREUR") -> Toast.makeText(this, "⚠️ $msg", Toast.LENGTH_LONG).show()
            msg.startsWith("!!") && msg.contains(",") -> {
                val parts = msg.removePrefix("!!").split(",")
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    addPosition(lat, lon, if (parts.size > 2) parts[2] else "?")
                }
            }
        }
    }

    private fun addPosition(lat: Double, lon: Double, alt: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvPositions.text = "📍 $time\n   Lat: $lat\n   Lon: $lon\n   Alt: $alt m\n\n${tvPositions.text}"
        val point = GeoPoint(lat, lon)
        mapView.overlays.add(Marker(mapView).apply {
            position = point
            title = "$time"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        })
        mapView.controller.animateTo(point)
        mapView.invalidate()
        Toast.makeText(this, "📍 Marqueur ajouté !", Toast.LENGTH_SHORT).show()
    }

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvPositions.text = "[$time] $text\n\n${tvPositions.text}"
    }

    private fun clearMap() {
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
        unregisterReceiver(smsReceiver)
    }
}
