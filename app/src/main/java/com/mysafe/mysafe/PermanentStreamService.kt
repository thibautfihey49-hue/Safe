package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PermanentStreamService : Service() {
    companion object {
        const val TAG = "MySafeStream"
        const val CHANNEL_ID = "stream_permanent"
        const val NOTIFICATION_ID = 792
        
        const val ACTION_DEMARRER_CAMERA = "com.mysafe.mysafe.DEMARRER_CAMERA"
        const val ACTION_DEMARRER_RECEPTION = "com.mysafe.mysafe.DEMARRER_RECEPTION"
        const val ACTION_ARRETER = "com.mysafe.mysafe.ARRETER"
        
        const val RELAIS_IP = "51.15.221.168"
        const val RELAIS_PORT = 8888

        var statusCallback: ((String) -> Unit)? = null
        var frameListener: ((ByteArray, Int, Int) -> Unit)? = null
        var isRunning = AtomicBoolean(false)
    }

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var socket: Socket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var monAppareil: Appareil? = null
    private var partenaire: Appareil? = null
    private var executor: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        monAppareil = AppairageManager.getMonAppareil(this)
        partenaire = AppairageManager.getPartenaire(this)
        executor = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMARRER_CAMERA -> demarrerCameraEtEnvoi()
            ACTION_DEMARRER_RECEPTION -> demarrerReception()
            ACTION_ARRETER -> arreterTout()
        }
        return START_STICKY
    }

    private fun notifierStatut(texte: String) {
        statusCallback?.invoke(texte)
    }

    private fun demarrerCameraEtEnvoi() {
        if (isRunning.get()) return
        if (monAppareil == null) { 
            notifierStatut("Appareil non configure"); return 
        }
        
        isRunning.set(true)
        startForeground(NOTIFICATION_ID, buildNotification("Demarrage camera + audio..."))
        notifierStatut("Connexion au serveur relais...")
        
        scope.launch {
            try {
                socket = Socket(RELAIS_IP, RELAIS_PORT)
                val out = DataOutputStream(socket!!.getOutputStream())
                
                out.writeUTF("CONNECT:${monAppareil!!.id}:${monAppareil!!.cleSecrete}")
                out.flush()
                
                notifierStatut("Connecte ! Camera + Micro actifs...")
                
                demarrerEnvoiAudio(out)
                demarrerEnvoiCamera(out)
                
            } catch (e: Exception) {
                notifierStatut("Erreur connexion: ${e.message}")
                isRunning.set(false)
            }
        }
    }

    private fun demarrerReception() {
        if (isRunning.get()) return
        if (partenaire == null || monAppareil == null) { 
            notifierStatut("Appairez d'abord les 2 telephones !")
            return
        }
        
        isRunning.set(true)
        startForeground(NOTIFICATION_ID, buildNotification("Connexion a ${partenaire!!.nom}..."))
        
        val viewerIntent = Intent(this, VideoViewerActivity::class.java)
        viewerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(viewerIntent)
        
        notifierStatut("Demande de connexion a ${partenaire!!.nom}...")
        
        scope.launch {
            try {
                socket = Socket(RELAIS_IP, RELAIS_PORT)
                val out = DataOutputStream(socket!!.getOutputStream())
                val `in` = DataInputStream(socket!!.getInputStream())
                
                out.writeUTF("REQUEST:${partenaire!!.id}:${partenaire!!.cleSecrete}:${monAppareil!!.id}")
                out.flush()
                
                val reponse = `in`.readUTF()
                if (!reponse.startsWith("OK")) {
                    notifierStatut(reponse)
                    arreterTout()
                    return@launch
                }
                
                notifierStatut("${partenaire!!.nom} est CONNECTE ! Reception video + audio...")
                runOnUiThread {
                    Toast.makeText(this@PermanentStreamService, "En direct de ${partenaire!!.nom} !", Toast.LENGTH_LONG).show()
                }
                
                demarrerReceptionAudio(`in`)
                demarrerReceptionVideo(`in`)
                
            } catch (e: Exception) {
                notifierStatut("Erreur: ${e.message}")
                isRunning.set(false)
            }
        }
    }

    private fun demarrerEnvoiAudio(out: DataOutputStream) {
        scope.launch {
            try {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@launch
                
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                
                audioRecord!!.startRecording()
                val buffer = ByteArray(bufferSize)
                
                while (isRunning.get()) {
                    val read = audioRecord!!.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        try {
                            out.write('A'.code)  // Int, pas Byte
                            out.writeInt(read)
                            out.write(buffer, 0, read)
                            out.flush()
                        } catch (e: Exception) { break }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio envoi: ${e.message}")
            }
        }
    }

    private fun demarrerEnvoiCamera(out: DataOutputStream) {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            
            val cameraId = cameraManager!!.cameraIdList.firstOrNull() ?: return
            val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            val size = sizes.firstOrNull { it.width <= 640 && it.height <= 480 } ?: sizes[0]
            
            cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    try {
                        val reader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                        
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraCaptureSession = session
                                val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                }
                                
                                reader.setOnImageAvailableListener({ reader ->
                                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    image.close()
                                    
                                    try {
                                        out.write('V'.code)  // Int, pas Byte
                                        out.writeInt(bytes.size)
                                        out.writeInt(size.width)
                                        out.writeInt(size.height)
                                        out.write(bytes)
                                        out.flush()
                                    } catch (e: Exception) {}
                                }, null)
                                
                                executor?.scheduleAtFixedRate({
                                    try {
                                        session.capture(request.build(), null, null)
                                    } catch (e: Exception) {}
                                }, 0, 200, TimeUnit.MILLISECONDS)
                            }
                            override fun onConfigureFailed(p0: CameraCaptureSession) {}
                        }, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera: ${e.message}")
                    }
                }
                override fun onDisconnected(p0: CameraDevice) { arreterTout() }
                override fun onError(p0: CameraDevice, p1: Int) { arreterTout() }
            }, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ouverture camera: ${e.message}")
        }
    }

    private fun demarrerReceptionAudio(`in`: DataInputStream) {
        scope.launch {
            try {
                val sampleRate = 16000
                val channelOut = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
                
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .setEncoding(encoding)
                        .build())
                    .setBufferSizeInBytes(bufferSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                
                audioTrack!!.play()
                val buffer = ByteArray(bufferSize * 2)
                
                while (isRunning.get()) {
                    try {
                        val type = `in`.read()  // Retourne Int, pas Byte
                        if (type == 'A'.code) {
                            val size = `in`.readInt()
                            if (size > buffer.size) continue
                            `in`.readFully(buffer, 0, size)
                            audioTrack!!.write(buffer, 0, size)
                        }
                    } catch (e: Exception) { break }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reception audio: ${e.message}")
            }
        }
    }

    private fun demarrerReceptionVideo(`in`: DataInputStream) {
        scope.launch {
            val buffer = ByteArray(200 * 1024)
            while (isRunning.get()) {
                try {
                    val type = `in`.read()  // Retourne Int, pas Byte
                    if (type == 'V'.code) {
                        val size = `in`.readInt()
                        val width = `in`.readInt()
                        val height = `in`.readInt()
                        if (size > buffer.size || size <= 0) continue
                        `in`.readFully(buffer, 0, size)
                        
                        val frame = ByteArray(size)
                        System.arraycopy(buffer, 0, frame, 0, size)
                        frameListener?.invoke(frame, width, height)
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun arreterTout() {
        isRunning.set(false)
        scope.cancel()
        executor?.shutdownNow()
        
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        
        audioTrack?.apply { stop(); release() }
        audioTrack = null
        
        cameraCaptureSession?.close()
        cameraDevice?.close()
        cameraCaptureSession = null
        cameraDevice = null
        
        socket?.close()
        socket = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        runOnUiThread { 
            notifierStatut("Streaming arrete")
            Toast.makeText(this, "Arrete", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe — Flux en direct")
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
            NotificationChannel(CHANNEL_ID, "Flux Video+Audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Transmission discrete camera + micro"
                setShowBadge(false)
                enableVibration(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }
        }
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    override fun onDestroy() {
        super.onDestroy()
        arreterTout()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
