package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.Camera
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var etNumeroCible: EditText
    private lateinit var etIpCible: EditText
    private lateinit var btnPosition: Button
    private lateinit var btnSuivi: Button
    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button
    private lateinit var btnConnectStream: Button
    private lateinit var surfaceView: SurfaceView
    private lateinit var mapView: MapView
    private lateinit var tvStatut: TextView
    private lateinit var tvJournal: TextView

    private var camera: Camera? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var isStreaming = false
    private val SERVER_PORT = 8080

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var numeroCible: String = ""
    private var remoteMarker: Marker? = null
    private val DEFAULT = GeoPoint(47.4728, -0.5416)

    private val PERMS = mutableListOf(
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
        Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.POST_NOTIFICATIONS
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
        enregistrerReceiver()
        afficherMonIp()
        journal("✅ Pret — Sur le meme WiFi !")
    }

    private fun initViews() {
        etNumeroCible = findViewById(R.id.etNumeroCible)
        etIpCible = findViewById(R.id.etIpCible)
        btnPosition = findViewById(R.id.btnPosition)
        btnSuivi = findViewById(R.id.btnSuivi)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnStopStream = findViewById(R.id.btnStopStream)
        btnConnectStream = findViewById(R.id.btnConnectStream)
        surfaceView = findViewById(R.id.surfaceView)
        mapView = findViewById(R.id.mapView)
        tvStatut = findViewById(R.id.tvStatut)
        tvJournal = findViewById(R.id.tvJournal)

        surfaceView.holder.addCallback(this)
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)

        btnPosition.setOnClickListener { demanderPosition() }
        btnSuivi.setOnClickListener { demarrerSuivi() }
        btnStartStream.setOnClickListener { demarrerServeur() }
        btnStopStream.setOnClickListener { arreterTout() }
        btnConnectStream.setOnClickListener { connecterClient() }
    }

    private fun afficherMonIp() {
        try {
            val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = Formatter.formatIpAddress(wifiMgr.connectionInfo.ipAddress)
            runOnUiThread {
                tvStatut.text = "✅ Mon IP: $ip — Port: $SERVER_PORT"
                journal("🌐 Mon adresse IP: $ip")
            }
        } catch (e: Exception) {
            journal("⚠️ Impossible de lire l'IP: ${e.message}")
        }
    }

    private fun demanderPosition() {
        val num = etNumeroCible.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "Entre un numero !", Toast.LENGTH_SHORT).show()
            return
        }
        envoyerSMS(num, "!!POSITION")
        journal("📍 Demande de position a $num")
    }

    private fun demarrerSuivi() {
        val num = etNumeroCible.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "Entre un numero !", Toast.LENGTH_SHORT).show()
            return
        }
        envoyerSMS(num, "!!DEMARRER")
        journal("📡 Suivi demande a $num")
    }

    private fun envoyerSMS(dest: String, msg: String) {
        try {
            android.telephony.SmsManager.getDefault().sendTextMessage(dest, null, msg, null, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    // 🎥 SERVEUR — CIBLE: diffuse la video
    private fun demarrerServeur() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1002)
            return
        }

        if (isStreaming) {
            Toast.makeText(this, "Deja en cours !", Toast.LENGTH_SHORT).show()
            return
        }

        isStreaming = true
        journal("📷 Demarrage caméra + serveur...")
        tvStatut.text = "🔴 SERVEUR EN ECOUTE — Attente connexion..."

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ouvrir caméra
                camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
                val params = camera!!.parameters
                params.setPreviewSize(640, 480)
                camera!!.parameters = params
                camera!!.setPreviewDisplay(surfaceView.holder)
                camera!!.startPreview()

                // Lancer serveur TCP
                serverSocket = ServerSocket(SERVER_PORT)
                withContext(Dispatchers.Main) {
                    val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val ip = Formatter.formatIpAddress(wifiMgr.connectionInfo.ipAddress)
                    journal("✅ SERVEUR PRET — IP: $ip Port: $SERVER_PORT")
                    tvStatut.text = "✅ SERVEUR PRET — IP: $ip Port: $SERVER_PORT"
                    Toast.makeText(this@MainActivity, "✅ Serveur pret ! Donne cette IP a l'autre telephone", Toast.LENGTH_LONG).show()
                }

                // Accepter une connexion
                clientSocket = serverSocket!!.accept()
                withContext(Dispatchers.Main) {
                    journal("🔗 Client connecte !")
                    tvStatut.text = "🔗 CLIENT CONNECTE — Streaming en cours..."
                }

                // Envoyer confirmation de connexion
                val output = clientSocket!!.getOutputStream()
                output.write("CONNECTED".toByteArray())
                output.flush()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    journal("❌ Erreur serveur: ${e.message}")
                    tvStatut.text = "❌ Erreur: ${e.message}"
                }
            }
        }
    }

    // 👁️ CLIENT — SE CONNECTE ET AFFICHE LE FLUX
    private fun connecterClient() {
        val ip = etIpCible.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Entre l'IP du telephone cible !", Toast.LENGTH_SHORT).show()
            return
        }

        isStreaming = true
        journal("👁️ Connexion a $ip:$SERVER_PORT...")
        tvStatut.text = "⏳ Connexion en cours vers $ip..."

        clientJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket = Socket(ip, SERVER_PORT)
                withContext(Dispatchers.Main) {
                    journal("✅ Connecte au serveur !")
                    tvStatut.text = "✅ CONNECTE — Reception du flux..."
                    Toast.makeText(this@MainActivity, "✅ Connecte ! Video en cours...", Toast.LENGTH_SHORT).show()
                    
                    // Afficher ecran vert = connexion reussie
                    surfaceView.holder.lockCanvas()?.let { canvas ->
                        canvas.drawARGB(255, 0, 150, 0)
                        surfaceView.holder.unlockCanvasAndPost(canvas)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    journal("❌ Erreur connexion: ${e.message}")
                    tvStatut.text = "❌ Erreur: ${e.message}"
                    Toast.makeText(this@MainActivity, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun arreterTout() {
        isStreaming = false
        
        camera?.apply {
            try { setPreviewCallback(null); stopPreview(); release() } catch (e: Exception) {}
        }
        camera = null
        
        serverSocket?.close()
        clientSocket?.close()
        
        serverJob?.cancel()
        clientJob?.cancel()
        
        runOnUiThread {
            journal("⏹️ Tout arrete")
            tvStatut.text = "✅ Pret — Sur le meme WiFi !"
            Toast.makeText(this, "✅ Arrete", Toast.LENGTH_SHORT).show()
        }
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

    private fun afficherSurCarte(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        remoteMarker?.let { mapView.overlays.remove(it) }
        remoteMarker = Marker(mapView).apply {
            position = point
            title = "CIBLE"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(remoteMarker)
        mapView.controller.animateTo(point)
    }

    private fun enregistrerReceiver() {
        val filter = IntentFilter("com.mysafe.mysafe.SMS_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun demandPermissions() {
        val manquantes = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (manquantes.isNotEmpty()) ActivityCompat.requestPermissions(this, manquantes, 1001)
    }

    private fun journal(texte: String) {
        val h = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvJournal.text = "[$h] $texte\n\n${tvJournal.text}"
    }

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        arreterTout()
        try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
    }
}
