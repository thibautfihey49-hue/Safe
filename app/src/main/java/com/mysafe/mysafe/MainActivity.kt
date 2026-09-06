package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.*
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
import java.util.*

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
    
    private lateinit var etServerIp: EditText
    private lateinit var btnStartServer: Button
    private lateinit var btnStartClient: Button
    private lateinit var btnStopStream: Button
    private lateinit var tvStreamStatus: TextView
    private lateinit var surfaceView: SurfaceView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var userMarker: Marker? = null
    private var remoteMarker: Marker? = null
    private var targetPhoneNumber: String = ""
    private var myOwnNumber: String = ""
    private val DEFAULT_ANGERS = GeoPoint(47.4728, -0.5416)

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
    ).apply { 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra("sms_message")
            val sender = i?.getStringExtra("sender_number")
            if (msg != null) runOnUiThread { handleIncoming(msg, sender) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initViews()
            checkPerms()
            getMyPhoneNumber()
            setupMap()
            setupFusedLocation()
            setupWifiStreamPanel()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsReceiver, IntentFilter("com.mysafe.mysafe.SMS_RECEIVED"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(smsReceiver, IntentFilter("com.mysafe.mysafe.SMS_RECEIVED"))
            }
            WifiStreamService.statusCallback = { runOnUiThread { tvStreamStatus.text = it } }
            
            addLog("✅ MySafe PRÊT — Streaming WIFI disponible !")
        } catch (e: Exception) {
            Log.e("MySafe", "Erreur onCreate: ${e.message}", e)
            Toast.makeText(this, "❌ Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        
        etServerIp = findViewById(R.id.etServerIp)
        btnStartServer = findViewById(R.id.btnStartServer)
        btnStartClient = findViewById(R.id.btnStartClient)
        btnStopStream = findViewById(R.id.btnStopStream)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)
        surfaceView = findViewById(R.id.surfaceView)

        btnMyPosition.setOnClickListener { getMyPosition() }
        btnGetHisPosition.setOnClickListener { askHisPosition() }
        btnDemarrer.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnFloatingMap.setOnClickListener { openFloatingMap() }
        btnClearHistory.setOnClickListener { clearMap() }
    }

    private fun setupWifiStreamPanel() {
        // 📡 TÉLÉPHONE CIBLE = bouton "Devenir serveur"
        btnStartServer.setOnClickListener {
            if (!checkWifiPerms()) {
                Toast.makeText(this, "⚠️ Activez le Wi-Fi d'abord !", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(this, WifiStreamService::class.java)
            intent.action = WifiStreamService.ACTION_START_SERVER
            startForegroundServiceSafe(intent)
            Toast.makeText(this, "📡 Serveur démarré — Note l'IP affichée !", Toast.LENGTH_LONG).show()
        }

        // 📱 TÉLÉPHONE DE CONTRÔLE = bouton "Se connecter"
        btnStartClient.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "⚠️ Entrez l'IP du serveur", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, WifiStreamService::class.java)
            intent.action = WifiStreamService.ACTION_START_CLIENT
            intent.putExtra("server_ip", ip)
            startForegroundServiceSafe(intent)
            Toast.makeText(this, "📱 Connexion à $ip...", Toast.LENGTH_SHORT).show()
        }

        // ⏹️ STOP
        btnStopStream.setOnClickListener {
            val intent = Intent(this, WifiStreamService::class.java)
            intent.action = WifiStreamService.ACTION_STOP
            startForegroundServiceSafe(intent)
            Toast.makeText(this, "⏹️ Streaming arrêté", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkWifiPerms(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundServiceSafe(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MySafe", "Erreur service: ${e.message}")
        }
    }

    private fun getMyPhoneNumber() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                myOwnNumber = normalizeNumber(tm.line1Number ?: "")
                SmsReceiver.myPhoneNumber = myOwnNumber
                addLog("📱 Mon numéro : $myOwnNumber")
            }
        } catch (e: Exception) {
            addLog("📱 Impossible de lire le numéro")
        }
    }

    private fun setupMap() {
        try {
            Configuration.getInstance().apply {
                osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
                osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")
                userAgentValue = "MySafe-App"
            }
            mapView.apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(DEFAULT_ANGERS) }
        } catch (e: Exception) {
            Toast.makeText(this, "⚠️ Carte: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFusedLocation() {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationRequest = LocationRequest.create().apply { interval = 5000; fastestInterval = 2000; priority = LocationRequest.PRIORITY_HIGH_ACCURACY; smallestDisplacement = 2f }
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { showMyPosition(it) } }
            }
        } catch (e: Exception) {
            Log.e("MySafe", "Erreur location: ${e.message}")
        }
    }

    private fun getMyPosition() {
        if (!hasLocationPerm()) { Toast.makeText(this, "❌ Autorisez la position", Toast.LENGTH_LONG).show(); return }
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && loc.latitude != 0.0) showMyPosition(loc) else requestFreshLoc()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur GPS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askHisPosition() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "⚠️ Entrez un numéro cible", Toast.LENGTH_SHORT).show(); return }
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) {
            Toast.makeText(this, "🚫 Entrez le numéro de l'AUTRE téléphone !", Toast.LENGTH_LONG).show(); return
        }
        targetPhoneNumber = norm
        addLog("📤 Demande de position à $targetPhoneNumber")
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!POSITION")
        startForegroundServiceSafe(svc)
        Toast.makeText(this, "📩 Demande envoyée ! Attends la réponse...", Toast.LENGTH_LONG).show()
    }

    private fun startTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "⚠️ Entrez un numéro cible d'abord !", Toast.LENGTH_SHORT).show(); return }
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) { Toast.makeText(this, "🚫 Pas vous-même !", Toast.LENGTH_LONG).show(); return }
        targetPhoneNumber = norm
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!DEMARRER")
        startForegroundServiceSafe(svc)
        addLog("✅ Suivi continu démarré vers $targetPhoneNumber")
        Toast.makeText(this, "✅ Suivi démarré", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) return
        targetPhoneNumber = normalizeNumber(num)
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!STOP")
        startForegroundServiceSafe(svc)
        addLog("⏹️ Suivi arrêté")
        Toast.makeText(this, "⏹️ Suivi arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun requestFreshLoc() {
        if (!hasLocationPerm()) return
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
            val req = LocationRequest.create().apply { numUpdates = 1; priority = LocationRequest.PRIORITY_HIGH_ACCURACY }
            fusedLocationClient.requestLocationUpdates(req, locationCallback!!, mainLooper)
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur GPS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMyPosition(loc: Location) {
        addLog("📍 MOI: ${loc.latitude}, ${loc.longitude} — ${loc.accuracy.toInt()}m")
        val pt = GeoPoint(loc.latitude, loc.longitude)
        userMarker?.let { mapView.overlays.remove(it) }
        userMarker = Marker(mapView).apply { position = pt; title = "🟢 MOI"; icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation) }
        mapView.overlays.add(userMarker)
        mapView.controller.animateTo(pt)
    }

    private fun handleIncoming(msg: String, sender: String?) {
        if (!msg.startsWith("!!") || msg in listOf("!!POSITION", "!!DEMARRER", "!!STOP")) return
        val parts = msg.removePrefix("!!").split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
            addLog("⚠️ Réponse invalide: $msg")
            return
        }
        addLog("🔴 LUI: $lat, $lon")
        val pt = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply { position = pt; title = "🔴 LUI"; icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_compass) }
        remoteMarker?.let { mapView.overlays.add(it) }
        mapView.controller.animateTo(pt)
        Toast.makeText(this, "🔴 Position reçue !", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun normalizeNumber(s: String) = s.replace("\\s".toRegex(), "").replace("-", "").let { if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it }
    private fun addLog(text: String) {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvPositions.text = "[$t] $text\n\n${tvPositions.text}"
    }
    private fun clearMap() {
        userMarker?.let { mapView.overlays.remove(it) }
        remoteMarker?.let { mapView.overlays.remove(it) }
        userMarker = null; remoteMarker = null
        mapView.invalidate()
        tvPositions.text = ""
        addLog("🗑️ Carte effacée")
    }
    private fun openFloatingMap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "⚠️ Autorise l'affichage par-dessus", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingMapWindow::class.java))
        Toast.makeText(this, "🗺️ Carte flottante ouverte", Toast.LENGTH_SHORT).show()
    }
    private fun checkPerms() {
        val missing = PERMS.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
    }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
