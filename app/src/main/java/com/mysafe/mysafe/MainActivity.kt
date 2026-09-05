package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
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

    private val PERMS = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    )

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

        // ✅ Correction : Ajout du drapeau RECEIVER_NOT_EXPORTED pour Android 13+
        val filter = IntentFilter(MySafeAgentService.SMS_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
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

        btnPosition.setOnClickListener { sendCommand("!!POSITION") }
        btnDemarrer.setOnClickListener { sendCommand("!!DEMARRER") }
        btnStop.setOnClickListener { sendCommand("!!STOP") }
        btnFloatingMap.setOnClickListener { openFloatingMap() }
        btnClearHistory.setOnClickListener { clearMap() }
    }

    private fun setupMap() {
        Configuration.getInstance().apply {
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        mapView.apply {
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.4728, -0.5416))
        }
    }

    private fun sendCommand(cmd: String) {
        val num = etTargetNumber.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "⚠️ Entrez un numéro d'abord !", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_PROCESS_COMMAND
            putExtra("sender_number", num)
            putExtra("command", cmd)
        }
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, "✅ Commande envoyée : $cmd", Toast.LENGTH_SHORT).show()
    }

    private fun handleIncomingMessage(msg: String) {
        when {
            msg.startsWith("!!OK-") -> {
                Toast.makeText(this, "✅ $msg", Toast.LENGTH_SHORT).show()
                addLog("✅ $msg")
            }
            msg.startsWith("!!") && msg.contains(",") -> {
                val parts = msg.removePrefix("!!").split(",")
                if (parts.size >= 2) {
                    try {
                        val lat = parts[0].toDouble()
                        val lon = parts[1].toDouble()
                        val alt = if (parts.size > 2) parts[2] else "?"
                        addPosition(lat, lon, alt)
                    } catch (e: NumberFormatException) {
                        addLog("📩 $msg")
                    }
                }
            }
            else -> addLog("📩 $msg")
        }
    }

    private fun addPosition(lat: Double, lon: Double, alt: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val log = "📍 $time\n   Lat: $lat\n   Lon: $lon\n   Alt: $alt m\n\n"
        tvPositions.text = "$log${tvPositions.text}"

        val point = GeoPoint(lat, lon)
        val marker = Marker(mapView).apply {
            position = point
            title = "$time - $lat / $lon"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvPositions.text = "[$time] $text\n\n${tvPositions.text}"
    }

    private fun clearMap() {
        mapView.overlays.clear()
        mapView.invalidate()
        tvPositions.text = "Aucune position..."
        Toast.makeText(this, "🗑️ Carte et historique effacés", Toast.LENGTH_SHORT).show()
    }

    private fun openFloatingMap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Autorisez l'affichage par-dessus les apps", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingMapWindow::class.java))
        Toast.makeText(this, "📌 Carte flottante ouverte", Toast.LENGTH_SHORT).show()
    }

    private fun checkPerms() {
        val missing = PERMS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }
}
