package com.mysafe.mysafe

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.BitmapFactory

class VideoViewerActivity : AppCompatActivity() {
    private lateinit var ivVideo: ImageView
    private lateinit var tvStatus: TextView
    private var estActif = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_viewer)
        
        ivVideo = findViewById(R.id.ivVideo)
        tvStatus = findViewById(R.id.tvVideoStatus)
        
        tvStatus.text = "En attente du flux..."
        
        PermanentStreamService.frameListener = { bytes, w, h ->
            if (estActif) {
                runOnUiThread {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            ivVideo.setImageBitmap(bitmap)
                            tvStatus.text = "EN DIRECT — ${w}x${h}"
                        }
                    } catch (e: Exception) {
                        tvStatus.text = "Erreur image: ${e.message}"
                    }
                }
            }
            // Pas de return du tout = la lambda se termine normalement
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        estActif = false
        PermanentStreamService.frameListener = null
    }

    fun fermerVue(v: View) {
        finish()
    }
}
