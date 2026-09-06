package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class WifiStreamService : Service() {
    companion object {
        const val TAG = "WifiStreamService"
        const val CHANNEL_ID = "wifi_stream_channel"
        const val NOTIFICATION_ID = 790
        const val PORT = 8888

        const val ACTION_START_SERVER = "com.mysafe.mysafe.START_SERVER"
        const val ACTION_START_CLIENT = "com.mysafe.mysafe.START_CLIENT"
        const val ACTION_STOP = "com.mysafe.mysafe.STOP"

        var serverAddress: String? = null
        var statusCallback: ((String) -> Unit)? = null
        var isRunning = AtomicBoolean(false)
        var surfaceView: SurfaceView? = null
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var cameraManager: CameraManager? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_START_CLIENT -> {
                val ip = intent.getStringExtra("server_ip") ?: return START_STICKY
                startClient(ip)
            }
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        startForeground(NOTIFICATION_ID, buildNotification("📡 Serveur en attente..."))
        
        Thread {
            try {
                serverSocket = ServerSocket(PORT, 1)
                val localIp = getLocalIpAddress()
                serverAddress = localIp
                runOnUiThread { 
                    statusCallback?.invoke("✅ Serveur démarré !\nIP: $localIp:$PORT\nEn attente de connexion...")
                    Toast.makeText(this, "📡 Serveur: $localIp:$PORT", Toast.LENGTH_LONG).show()
                }

                val client = serverSocket!!.accept()
                clientSocket = client
                runOnUiThread { statusCallback?.invoke("🔗 Connecté ! Streaming en cours...") }

                startAudioCapture()

            } catch (e: Exception) {
                Log.e(TAG, "Serveur erreur: ${e.message}")
                runOnUiThread { statusCallback?.invoke("❌ Erreur: ${e.message}") }
                stopAll()
            }
        }.start()
    }

    private fun startClient(serverIp: String) {
        if (isRunning.get()) return
        isRunning.set(true)
        
        startForeground(NOTIFICATION_ID, buildNotification("📱 Connexion à $serverIp..."))
        
        Thread {
            try {
                val socket = Socket(serverIp, PORT)
                socket.tcpNoDelay = true
                clientSocket = socket
                runOnUiThread { statusCallback?.invoke("✅ Connecté à $serverIp ! Réception en cours...") }

                startReceivingLoop(socket)

            } catch (e: Exception) {
                Log.e(TAG, "Client erreur: ${e.message}")
                runOnUiThread { statusCallback?.invoke("❌ Impossible de se connecter: ${e.message}") }
                stopAll()
            }
        }.start()
    }

    private fun startAudioCapture() {
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build()
            
            audioRecord!!.startRecording()
            
            scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isRunning.get()) {
                    val read = audioRecord!!.read(buffer, 0, bufferSize)
                    if (read > 0) sendAudioFrame(buffer.copyOf(read))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture: ${e.message}")
        }
    }

    private fun sendAudioFrame(data: ByteArray) {
        try {
            val out = clientSocket?.getOutputStream() ?: return
            val header = "AUDIO:${data.size}\n".toByteArray()
            out.write(header)
            out.write(data)
            out.flush()
        } catch (e: Exception) {}
    }

    private fun startReceivingLoop(socket: Socket) {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build())
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack!!.play()
            
            scope.launch {
                try {
                    val dataIn = DataInputStream(socket.getInputStream())
                    
                    while (isRunning.get()) {
                        val headerLine = StringBuilder()
                        while (true) {
                            val c = dataIn.read()
                            if (c == -1) break
                            if (c == '\n'.code) break
                            headerLine.append(c.toChar())
                        }
                        
                        val header = headerLine.toString()
                        if (header.startsWith("AUDIO:")) {
                            val size = header.removePrefix("AUDIO:").toInt()
                            val audioData = ByteArray(size)
                            dataIn.readFully(audioData)
                            audioTrack?.write(audioData, 0, size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Réception: ${e.message}")
                    runOnUiThread { statusCallback?.invoke("⏹️ Connexion perdue") }
                    stopAll()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe — Streaming WIFI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming WIFI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service de streaming audio par Wi-Fi local"
                setShowBadge(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopAll() {
        isRunning.set(false)
        scope.cancel()
        
        cameraDevice?.close()
        cameraDevice = null
        captureSession?.close()
        captureSession = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        clientSocket?.close()
        clientSocket = null
        serverSocket?.close()
        serverSocket = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        runOnUiThread { statusCallback?.invoke("⏹️ Streaming arrêté") }
    }

    private fun runOnUiThread(block: () -> Unit) {
        val mainHandler = android.os.Handler(mainLooper)
        mainHandler.post(block)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
