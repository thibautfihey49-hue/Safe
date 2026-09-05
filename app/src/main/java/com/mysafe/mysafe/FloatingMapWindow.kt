package com.mysafe.mysafe

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.config.Configuration
import java.io.File

class FloatingMapWindow : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            
            floatingView = layoutInflater.inflate(R.layout.floating_map, null)
            
            val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
            }
            
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 20
            params.y = 20
            
            windowManager?.addView(floatingView, params)
            
            val mapView = floatingView?.findViewById<org.osmdroid.views.MapView>(R.id.floatingMapView)
            if (mapView != null) {
                Configuration.getInstance().apply {
                    osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
                    osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")
                    userAgentValue = "MySafe-App"
                }
                mapView.setTileSource(TileSourceFactory.MAPNIK)
                mapView.setMultiTouchControls(true)
                mapView.controller.setZoom(12.0)
                mapView.controller.setCenter(GeoPoint(47.4728, -0.5416))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
