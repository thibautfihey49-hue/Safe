package com.mysafe.mysafe
import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class FloatingMapWindow : Service() {
    private lateinit var wm: WindowManager
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.floating_map, null)
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        else @Suppress("DEPRECATION") WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.END; params.x = 20; params.y = 100
        view.findViewById<MapView>(R.id.floatingMapView).apply {
            setMultiTouchControls(true); controller.setZoom(14.0); controller.setCenter(GeoPoint(47.4728, -0.5416))
        }
        wm.addView(view, params)
    }
    override fun onBind(i: Intent?) = null
}
