package com.mysafe.mysafe

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

class WifiDirectService : Service() {
    companion object {
        const val TAG = "WifiDirectService"
        const val CHANNEL_ID = "wifi_direct_channel"
        const val NOTIFICATION_ID = 791
        const val PORT = 8888

        const val ACTION_CREATE_GROUP = "com.mysafe.mysafe.CREATE_GROUP"
        const val ACTION_SEARCH_AND_CONNECT = "com.mysafe.mysafe.SEARCH_AND_CONNECT"
        const val ACTION_STOP = "com.mysafe.mysafe.STOP"

        var statusCallback: ((String) -> Unit)? = null
        var isRunning = AtomicBoolean(false)
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isGroupOwner = false

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager?.initialize(this, mainLooper, null)
        createNotificationChannel()
        registerWifiP2pReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CREATE_GROUP -> createGroup()
            ACTION_SEARCH_AND_CONNECT -> searchAndConnect()
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    // 📡 TÉLÉPHONE CIBLE = CRÉE LE GROUPE + ENVOIE SON AUDIO
    private fun createGroup() {
        if (isRunning.get()) return
        isRunning.set(true)
        isGroupOwner = true
        
        startForeground(NOTIFICATION_ID, buildNotification("📡 Création du groupe..."))
        
        wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                runOnUiThread { 
                    statusCallback?.invoke("✅ GROUPE CRÉÉ !\n📡 En attente de connexion...\n🎤 Ce téléphone envoie l'audio SEULEMENT")
                    Toast.makeText(this@WifiDirectService, "📡 Groupe créé — En attente...", Toast.LENGTH_LONG).show()
                }
                startTcpServer()
            }
            override fun onFailure(reason: Int) {
                runOnUiThread { statusCallback?.invoke("❌ Échec: $reason") }
                stopAll()
            }
        })
    }

    // 📱 TÉLÉPHONE DE CONTRÔLE = RECHERCHE + REÇOIT L'AUDIO (PAS D'ENVOI)
    private fun searchAndConnect() {
        if (isRunning.get()) return
        isRunning.set(true)
        isGroupOwner = false
        
        startForeground(NOTIFICATION_ID, buildNotification("🔍 Recherche d'appareils..."))
        runOnUiThread { statusCallback?.invoke("🔍 Recherche des appareils à proximité...") }
        
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                runOnUiThread { statusCallback?.invoke("❌ Impossible de lancer la recherche") }
                stopAll()
            }
        })
    }

    private fun startTcpServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT, 1)
                val localIp = getLocalIpAddress()
                
                runOnUiThread { 
                    statusCallback?.invoke("✅ Serveur prêt !\nIP: $localIp\n🎤 En attente de connexion pour ENVOI audio...")
                }

                val client = serverSocket!!.accept()
                clientSocket = client
                runOnUiThread { statusCallback?.invoke("🔗 Appareil connecté !\n🎤 ENVOI de l'audio en cours...") }
                
                // 🎤 SEUL LE SERVEUR ENVOIE — PAS DE RÉCEPTION
                startAudioSender(client)

            } catch (e: Exception) {
                Log.e(TAG, "Serveur: ${e.message}")
                runOnUiThread { statusCallback?.invoke("❌ Erreur: ${e.message}") }
                stopAll()
            }
        }
    }

    private fun connectToServer(serverIp: String) {
        scope.launch {
            try {
                runOnUiThread { statusCallback?.invoke("🔗 Connexion à $serverIp...") }
                val socket = Socket(serverIp, PORT)
                socket.tcpNoDelay = true
                clientSocket = socket
                
                runOnUiThread { statusCallback?.invoke("✅ CONNECTÉ !\n🔊 RÉCEPTION de l'audio en cours...") }
                Toast.makeText(this@WifiDirectService, "✅ Connecté — Vous entendez l'autre téléphone !", Toast.LENGTH_LONG).show()
                
                // 🔊 SEUL LE CLIENT REÇOIT — PAS D'ENVOI
                startAudioReceiver(socket)

            } catch (e: Exception) {
                Log.e(TAG, "Connexion: ${e.message}")
                runOnUiThread { statusCallback?.invoke("❌ Échec: ${e.message}") }
                stopAll()
            }
        }
    }

    // 🎤 SERVEUR — ENVOIE SEULEMENT (pas de réception)
    private fun startAudioSender(socket: Socket) {
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            
            val sampleRate = 16000
            val channelIn = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
            
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelIn)
                    .setEncoding(encoding)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build()
            
            audioRecord!!.startRecording()
            val out = socket.getOutputStream()
            val buffer = ByteArray(bufferSize)
            
            scope.launch {
                while (isRunning.get()) {
                    val read = audioRecord!!.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        try {
                            val header = "AUDIO:$read\n".toByteArray()
                            out.write(header)
                            out.write(buffer, 0, read)
                            out.flush()
                        } catch (e: Exception) { break }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio envoi: ${e.message}")
        }
    }

    // 🔊 CLIENT — REÇOIT SEULEMENT (pas d'envoi)
    private fun startAudioReceiver(socket: Socket) {
        try {
            val sampleRate = 16000
            val channelOut = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOut)
                    .setEncoding(encoding)
                    .build())
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack!!.play()
            
            scope.launch {
                try {
                    val `in` = DataInputStream(socket.getInputStream())
                    val buffer = ByteArray(bufferSize * 2)
                    
                    while (isRunning.get()) {
                        val headerLine = StringBuilder()
                        while (true) {
                            val c = `in`.read()
                            if (c == -1) throw EOFException()
                            if (c == '\n'.code) break
                            headerLine.append(c.toChar())
                        }
                        val header = headerLine.toString()
                        if (header.startsWith("AUDIO:")) {
                            val size = header.removePrefix("AUDIO:").toInt()
                            if (size > buffer.size) continue
                            `in`.readFully(buffer, 0, size)
                            audioTrack?.write(buffer, 0, size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Réception: ${e.message}")
                    runOnUiThread { statusCallback?.invoke("⏹️ Connexion perdue") }
                    stopAll()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio réception: ${e.message}")
        }
    }

    private fun registerWifiP2pReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (wifiP2pManager == null || isGroupOwner || !isRunning.get()) return
                        
                        wifiP2pManager?.requestPeers(channel) { peers ->
                            val device = peers?.deviceList?.firstOrNull()
                            if (device != null) {
                                runOnUiThread { statusCallback?.invoke("✅ Appareil trouvé: ${device.deviceName}\n🔗 Connexion...") }
                                
                                val config = WifiP2pConfig().apply {
                                    deviceAddress = device.deviceAddress
                                    wps.setup = WpsInfo.PBC
                                }
                                
                                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        runOnUiThread { statusCallback?.invoke("✅ Demande envoyée !\n✅ Acceptez sur l'autre téléphone") }
                                    }
                                    override fun onFailure(reason: Int) {
                                        runOnUiThread { statusCallback?.invoke("❌ Échec: $reason") }
                                    }
                                })
                            }
                        }
                    }
                    
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (info?.groupFormed == true && !info.isGroupOwner && !isGroupOwner) {
                            connectToServer(info.groupOwnerAddress.hostAddress)
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, intentFilter)
    }

    private fun getLocalIpAddress(): String {
        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.let { return it.hostAddress }
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
            .setContentTitle("MySafe — Audio Unidirectionnel")
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
            NotificationChannel(CHANNEL_ID, "Audio Direct", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Audio unidirectionnel — vous entendez l'autre"
                setShowBadge(false)
                enableVibration(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }
        }
    }

    private fun stopAll() {
        isRunning.set(false)
        scope.cancel()
        
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        
        audioTrack?.apply { stop(); release() }
        audioTrack = null
        
        clientSocket?.close()
        clientSocket = null
        serverSocket?.close()
        serverSocket = null
        
        if (isGroupOwner) {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        runOnUiThread { statusCallback?.invoke("⏹️ Déconnexion") }
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { receiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        stopAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
