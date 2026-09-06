package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
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
    private lateinit var tvMonCode: TextView
    private lateinit var etCodePartenaire: EditText
    private lateinit var btnApparier: Button
    private lateinit var tvAppairageStatut: TextView
    private lateinit var btnStartCamera: Button
    private lateinit var btnViewStream: Button
    private lateinit var btnStopAll: Button
    private lateinit var tvStreamStatus: TextView
    private lateinit var etTargetNumber: EditText
    private lateinit var tvPositions: TextView
    private lateinit var btnMyPosition: Button
    private lateinit var btnGetHisPosition: Button
    private lateinit var btnDemarrer: Button
    private lateinit var btnStopTracking: Button
    private lateinit var mapView: MapView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var monAppareil: Appareil? = null
    private var partenaire: Appareil? = null
    private var targetPhoneNumber: String = ""
    private var myOwnNumber: String = ""
    private var userMarker: Marker? = null
    private var remoteMarker: Marker? = null
    private val DEFAULT_ANGERS = GeoPoint(47.4728, -0.5416)

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_BOOT_COMPLETED
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
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
        initialiserAppairage()
        setupFusedLocation()
        setupMap()
        getMyPhoneNumber()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, IntentFilter("com.mysafe.mysafe.SMS_RECEIVED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, IntentFilter("com.mysafe.mysafe.SMS_RECEIVED"))
        }
        
        PermanentStreamService.statusCallback = { runOnUiThread { tvStreamStatus.text = it } }
        
        addLog("MySafe PRET — Camera + Audio a distance !")
    }

    private fun initViews() {
        tvMonCode = findViewById(R.id.tvMonCode)
        etCodePartenaire = findViewById(R.id.etCodePartenaire)
        btnApparier = findViewById(R.id.btnApparier)
        tvAppairageStatut = findViewById(R.id.tvAppairageStatut)
        btnStartCamera = findViewById(R.id.btnStartCamera)
        btnViewStream = findViewById(R.id.btnViewStream)
        btnStopAll = findViewById(R.id.btnStopAll)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)
        etTargetNumber = findViewById(R.id.etTargetNumber)
        tvPositions = findViewById(R.id.tvPositions)
        btnMyPosition = findViewById(R.id.btnMyPosition)
        btnGetHisPosition = findViewById(R.id.btnGetHisPosition)
        btnDemarrer = findViewById(R.id.btnDemarrer)
        btnStopTracking = findViewById(R.id.btnStopTracking)
        mapView = findViewById(R.id.mapView)
    }

    private fun initialiserAppairage() {
        monAppareil = AppairageManager.getMonAppareil(this)
        if (monAppareil == null) {
            monAppareil = AppairageManager.creerMonAppareil(this, "Mon Telephone", false)
        }
        
        val code = AppairageManager.genererCodeAssoc(monAppareil!!)
        tvMonCode.text = "MON CODE :\n$code"
        
        mettreAJourStatutAppairage()
    }

    private fun mettreAJourStatutAppairage() {
        if (AppairageManager.estAppaire(this)) {
            partenaire = AppairageManager.getPartenaire(this)
            tvAppairageStatut.text = "APPAIRE AVEC : ${partenaire?.nom ?: "Inconnu"}"
            tvAppairageStatut.setTextColor(android.graphics.Color.parseColor("#009933"))
            btnApparier.isEnabled = false
            btnApparier.setBackgroundColor(android.graphics.Color.GRAY)
        } else {
            tvAppairageStatut.text = "Non appaire — Entrez le code de l'autre telephone"
            tvAppairageStatut.setTextColor(android.graphics.Color.parseColor("#CC0000"))
        }
    }

    private fun apparier() {
        val code = etCodePartenaire.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(this, "Collez le code de l'autre telephone !", Toast.LENGTH_SHORT).show()
            return
        }
        if (AppairageManager.importerCodeAssoc(this, code)) {
            partenaire = AppairageManager.getPartenaire(this)
            mettreAJourStatutAppairage()
            Toast.makeText(this, "APPAIRAGE REUSSI ! Pret a diffuser !", Toast.LENGTH_LONG).show()
            addLog("Appaire avec: ${partenaire?.nom}")
        } else {
            Toast.makeText(this, "Code invalide !", Toast.LENGTH_SHORT).show()
        }
    }

    private fun demarrerCamera() {
        if (!verifierAppairage()) return
        if (!verifierPermissionsCameraAudio()) return
        
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_CAMERA
        startForegroundServiceSafe(intent)
        Toast.makeText(this, "Camera activee — En attente de connexion...", Toast.LENGTH_LONG).show()
        addLog("Camera + micro actifs sur cet appareil")
    }

    private fun demarrerReception() {
        if (!verifierAppairage()) return
        
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_RECEPTION
        startForegroundServiceSafe(intent)
        Toast.makeText(this, "Connexion au flux de ${partenaire?.nom}...", Toast.LENGTH_LONG).show()
        addLog("Demande de flux video + audio envoyee")
    }

    private fun arreterTout() {
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_ARRETER
        startForegroundService(intent)
        Toast.makeText(this, "Tout arrete", Toast.LENGTH_SHORT).show()
        addLog("Streaming arrete")
    }

    private fun verifierAppairage(): Boolean {
        if (!AppairageManager.estAppaire(this)) {
            Toast.makeText(this, "Appairez d'abord les 2 telephones !", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun verifierPermissionsCameraAudio(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cam || !mic) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1002)
            return false
        }
        return true
    }

    private fun setupFusedLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply { interval = 5000; fastestInterval = 2000; priority = LocationRequest.PRIORITY_HIGH_ACCURACY }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { addLog("MOI: ${it.latitude}, ${it.longitude}") } }
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
            Toast.makeText(this, "Carte: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMyPhoneNumber() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                myOwnNumber = normalizeNumber(tm.line1Number ?: "")
                SmsReceiver.myPhoneNumber = myOwnNumber
                addLog("Mon numero : $myOwnNumber")
            }
        } catch (e: Exception) {}
    }

    private fun getMyPosition() {
        if (!hasLocationPerm()) { Toast.makeText(this, "Autorisez la position", Toast.LENGTH_LONG).show(); return }
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && loc.latitude != 0.0) {
                addLog("MOI: ${loc.latitude}, ${loc.longitude} — ${loc.accuracy.toInt()}m")
                val pt = GeoPoint(loc.latitude, loc.longitude)
                userMarker?.let { mapView.overlays.remove(it) }
                userMarker = Marker(mapView).apply { position = pt; title = "MOI"; icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation) }
                userMarker?.let { mapView.overlays.add(it) }
                mapView.controller.animateTo(pt)
            }
        }
    }

    private fun askHisPosition() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "Entrez un numero cible", Toast.LENGTH_SHORT).show(); return }
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) {
            Toast.makeText(this, "Entrez le numero de l'AUTRE telephone !", Toast.LENGTH_LONG).show(); return
        }
        targetPhoneNumber = norm
        addLog("Demande de position a $targetPhoneNumber")
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!POSITION")
        startForegroundServiceSafe(svc)
        Toast.makeText(this, "Demande envoyee ! Attends la reponse...", Toast.LENGTH_LONG).show()
    }

    private fun startTracking() {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) { Toast.makeText(this, "Entrez un numero cible d'abord !", Toast.LENGTH_SHORT).show(); return }
        val norm = normalizeNumber(num)
        if (myOwnNumber.isNotEmpty() && norm == myOwnNumber) { Toast.makeText(this, "Pas vous-meme !", Toast.LENGTH_LONG).show(); return }
        targetPhoneNumber = norm
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", targetPhoneNumber)
        svc.putExtra("command", "!!DEMARRER")
        startForegroundServiceSafe(svc)
        addLog("Suivi continu demarre vers $targetPhoneNumber")
        Toast.makeText(this, "Suivi demarre", Toast.LENGTH_SHORT).show()
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
        addLog("Suivi arrete")
        Toast.makeText(this, "Suivi arrete", Toast.LENGTH_SHORT).show()
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

    private fun handleIncoming(msg: String, sender: String?) {
        if (!msg.startsWith("!!") || msg in listOf("!!POSITION", "!!DEMARRER", "!!STOP")) return
        val parts = msg.removePrefix("!!").split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
            addLog("Reponse invalide: $msg")
            return
        }
        addLog("LUI: $lat, $lon")
        val pt = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply { position = pt; title = "LUI"; icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_compass) }
        remoteMarker?.let { mapView.overlays.add(it) }
        mapView.controller.animateTo(pt)
        Toast.makeText(this, "Position recue !", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun normalizeNumber(s: String) = s.replace("\\s".toRegex(), "").replace("-", "").let { if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it }
    private fun addLog(text: String) {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvPositions.text = "[$t] $text\n\n${tvPositions.text}"
    }
    private fun checkPermissions() {
        val missing = PERMS.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
    }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
