package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Surface
import android.view.TextureView
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
    
    private lateinit var tvTitle: TextView
    private lateinit var layoutSecretPanel: LinearLayout
    private lateinit var etServerUrl: EditText
    private lateinit var textureView: TextureView
    private lateinit var btnCamFront: Button
    private lateinit var btnCamBack: Button
    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button
    private lateinit var btnMicToggle: Button
    private lateinit var btnSpeakToggle: Button
    private lateinit var btnCloseSecret: Button
    private lateinit var tvStreamStatus: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var userMarker: Marker? = null
    private var remoteMarker: Marker? = null
    private var targetPhoneNumber: String = ""
    private var myOwnNumber: String = ""
    private val DEFAULT_ANGERS = GeoPoint(47.4728, -0.5416)

    private var titleClickCount = 0
    private var lastTitleClickTime = 0L
    private var currentCameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isMicOn = false
    private var isSpeakOn = false

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.SYSTEM_ALERT_WINDOW, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.RECEIVE_BOOT_COMPLETED
    ).apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra("sms_message")
            val sender = i?.getStringExtra("sender_number")
            if (msg != null) runOnUiThread { handleIncoming(msg, sender) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPerms()
        getMyPhoneNumber()
        setupMap()
        setupFusedLocation()
        setupSecretPanel()
        
        registerReceiver(smsReceiver, IntentFilter("com.mysafe.mysafe.SMS_RECEIVED"))
        CameraStreamService.statusCallback = { runOnUiThread { tvStreamStatus.text = it } }
        
        addLog("✅ MySafe PRÊT — 5x sur le titre pour caméra")
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
        
        tvTitle = findViewById(R.id.tvTitle)
        layoutSecretPanel = findViewById(R.id.layoutSecretPanel)
        etServerUrl = findViewById(R.id.etServerUrl)
        textureView = findViewById(R.id.surfaceView)
        btnCamFront = findViewById(R.id.btnCamFront)
        btnCamBack = findViewById(R.id.btnCamBack)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnStopStream = findViewById(R.id.btnStopStream)
        btnMicToggle = findViewById(R.id.btnMicToggle)
        btnSpeakToggle = findViewById(R.id.btnSpeakToggle)
        btnCloseSecret = findViewById(R.id.btnCloseSecret)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)

        btnMyPosition.setOnClickListener { getMyPosition() }
        btnGetHisPosition.setOnClickListener { askHisPosition() }
        btnDemarrer.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnFloatingMap.setOnClickListener { openFloatingMap() }
        btnClearHistory.setOnClickListener { clearMap() }
        
        tvTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTitleClickTime > 2000) titleClickCount = 0
            lastTitleClickTime = now
            if (++titleClickCount >= 5) {
                titleClickCount = 0
                layoutSecretPanel.visibility = if (layoutSecretPanel.visibility == View.GONE) {
                    Toast.makeText(this, "🔒 Mode secret activé", Toast.LENGTH_SHORT).show()
                    View.VISIBLE
                } else View.GONE
            }
        }
    }

    private fun setupSecretPanel() {
        btnCamFront.setOnClickListener {
            currentCameraFacing = CameraCharacteristics.LENS_FACING_FRONT
            btnCamFront.setBackgroundColor(0xFF006633.toInt())
            btnCamBack.setBackgroundColor(0xFF222222.toInt())
            Toast.makeText(this, "📷 Caméra Avant", Toast.LENGTH_SHORT).show()
        }
        btnCamBack.setOnClickListener {
            currentCameraFacing = CameraCharacteristics.LENS_FACING_BACK
            btnCamBack.setBackgroundColor(0xFF006633.toInt())
            btnCamFront.setBackgroundColor(0xFF222222.toInt())
            Toast.makeText(this, "📷 Caméra Arrière", Toast.LENGTH_SHORT).show()
        }
        btnStartStream.setOnClickListener { startStreaming() }
        btnStopStream.setOnClickListener { stopStreaming() }
        
        btnMicToggle.setOnClickListener {
            isMicOn = !isMicOn
            btnMicToggle.setBackgroundColor(if (isMicOn) 0xFF006633.toInt() else 0xFF222222.toInt())
            val intent = Intent(this, CameraStreamService::class.java)
            intent.action = CameraStreamService.ACTION_TOGGLE_MIC
            startForegroundService(intent)
        }
        btnSpeakToggle.setOnClickListener {
            isSpeakOn = !isSpeakOn
            btnSpeakToggle.setBackgroundColor(if (isSpeakOn) 0xFF006633.toInt() else 0xFF222222.toInt())
            val intent = Intent(this, CameraStreamService::class.java)
            intent.action = CameraStreamService.ACTION_TOGGLE_SPEAK
            startForegroundService(intent)
        }
        btnCloseSecret.setOnClickListener { layoutSecretPanel.visibility = View.GONE }
        
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                CameraStreamService.surface = Surface(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                CameraStreamService.surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun startStreaming() {
        val url = etServerUrl.text.toString().trim()
        if (url.isEmpty()) { Toast.makeText(this, "⚠️ Entrez l'URL du serveur", Toast.LENGTH_SHORT).show(); return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "⚠️ Autorisez Caméra et Micro", Toast.LENGTH_SHORT).show(); checkPerms(); return
        }
        val intent = Intent(this, CameraStreamService::class.java)
        intent.action = CameraStreamService.ACTION_START_STREAM
        intent.putExtra("server_url", url)
        intent.putExtra("camera_facing", currentCameraFacing)
        startForegroundService(intent)
        Toast.makeText(this, "⏳ Connexion...", Toast.LENGTH_SHORT).show()
    }

    private fun stopStreaming() {
        val intent = Intent(this, CameraStreamService::class.java)
        intent.action = CameraStreamService.ACTION_STOP_STREAM
        startForegroundService(intent)
        isMicOn = false; isSpeakOn = false
        btnMicToggle.setBackgroundColor(0xFF222222.toInt())
        btnSpeakToggle.setBackgroundColor(0xFF222222.toInt())
    }

    private fun getMyPhoneNumber() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            myOwnNumber = normalizeNumber(tm.line1Number ?: "")
            SmsReceiver.myPhoneNumber = myOwnNumber
            addLog("📱 Mon numéro : $myOwnNumber")
        }
    }

    private fun setupMap() {
        Configuration.getInstance().apply {
            osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
            osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")
            userAgentValue = "MySafe-App"
        }
        mapView.apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(DEFAULT_ANGERS) }
    }

    private fun setupFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply { interval = 5000; fastestInterval = 2000; priority = LocationRequest.PRIORITY_HIGH_ACCURACY; smallestDisplacement = 2f }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { showMyPosition(it) } }
        }
    }

    private fun getMyPosition() {
        if (!hasLocationPerm()) { Toast.makeText(this, "❌ Autorisez la position", Toast.LENGTH_LONG).show(); return }
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && loc.latitude != 0.0) showMyPosition(loc) else requestFreshLoc()
        }
    }

    private fun askHisPosition() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "⚠️ Entrez un numéro", Toast.LENGTH_SHORT).show(); return }
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) {
            Toast.makeText(this, "🚫 Pas toi-même !", Toast.LENGTH_LONG).show(); return
        }
        targetPhoneNumber = norm
        addLog("📤 Demande à $targetPhoneNumber")
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!POSITION")
        startForegroundService(svc)
        Toast.makeText(this, "📩 Demande envoyée !", Toast.LENGTH_LONG).show()
    }

    private fun startTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) return
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) { Toast.makeText(this, "🚫 Pas toi-même !", Toast.LENGTH_LONG).show(); return }
        targetPhoneNumber = norm
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!DEMARRER")
        startForegroundService(svc)
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
        startForegroundService(svc)
        Toast.makeText(this, "✅ Suivi arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun requestFreshLoc() {
        if (!hasLocationPerm()) return
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val req = LocationRequest.create().apply { numUpdates = 1; priority = LocationRequest.PRIORITY_HIGH_ACCURACY }
        fusedLocationClient.requestLocationUpdates(req, locationCallback!!, mainLooper)
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
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) return
        addLog("🔴 LUI: $lat, $lon")
        val pt = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply { position = pt; title = "🔴 LUI"; icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_compass) }
        remoteMarker?.let { mapView.overlays.add(it) }
        mapView.controller.animateTo(pt)
        Toast.makeText(this, "🔴 Position reçue", Toast.LENGTH_SHORT).show()
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
            return
        }
        startService(Intent(this, FloatingMapWindow::class.java))
    }
    private fun checkPerms() {
        val missing = PERMS.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
    }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(smsReceiver); locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) } }
}
