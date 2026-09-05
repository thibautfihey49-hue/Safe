package com.mysafe.mysafe
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private val PERMS = arrayOf(
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.SYSTEM_ALERT_WINDOW,
        android.Manifest.permission.RECEIVE_BOOT_COMPLETED
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(47.4728, -0.5416))
        findViewById<Button>(R.id.btnFloatingMap).setOnClickListener { openFloating() }
        findViewById<Button>(R.id.btnClearHistory).setOnClickListener { mapView.overlays.clear(); mapView.invalidate() }
        checkPerms()
    }

    private fun checkPerms() {
        val missing = PERMS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 1001)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun openFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez l'affichage par-dessus", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingMapWindow::class.java))
        Toast.makeText(this, "Carte flottante ouverte ✅", Toast.LENGTH_SHORT).show()
    }
}
