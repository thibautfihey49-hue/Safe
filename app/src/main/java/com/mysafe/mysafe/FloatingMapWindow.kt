package com.mysafe.mysafe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class FloatingMapWindow : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var mapView: MapView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingWindow()
    }

    private fun createFloatingWindow() {
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                600,
                400,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                600,
                400,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        }
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 100

        mapView = MapView(this)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(47.4728, -0.5416))
        windowManager.addView(mapView, params)
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) windowManager.removeView(mapView)
    }
}
