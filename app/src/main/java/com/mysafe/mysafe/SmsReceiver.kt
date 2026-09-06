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
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.action)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val messageComplet = StringBuilder()
        var numeroExpediteur = ""

        for (msg in messages) {
            messageComplet.append(msg.messageBody)
            if (numeroExpediteur.isEmpty()) numeroExpediteur = msg.originatingAddress ?: ""
        }

        val message = messageComplet.toString().trim()
        Log.d("SmsReceiver", "SMS recu de $numeroExpediteur : $message")

        // Traiter les commandes
        when {
            message == "!!POSITION" -> repondrePosition(context, numeroExpediteur)
            message == "!!DEMARRER" -> demarrerSuivi(context, numeroExpediteur)
            message == "!!STOP" -> arreterSuivi(context, numeroExpediteur)
            message.startsWith("!!") -> traiterPosition(context, numeroExpediteur, message)
        }
    }

    private fun repondrePosition(context: Context, destinataire: String) {
        Log.d("SmsReceiver", "Reponse position a $destinataire")
        val reponse = "!!0.0,0.0" // Sera remplacee par la vraie position
        envoyerSMSInvisible(destinataire, reponse)
    }

    private fun demarrerSuivi(context: Context, destinataire: String) {
        Log.d("SmsReceiver", "Demarrage suivi demande par $destinataire")
        val intent = Intent(context, MySafeAgentService::class.java)
        intent.action = MySafeAgentService.ACTION_SEND_COMMAND
        intent.putExtra("target_number", destinataire)
        intent.putExtra("command", "!!DEMARRER")
        context.startForegroundService(intent)
    }

    private fun arreterSuivi(context: Context, destinataire: String) {
        Log.d("SmsReceiver", "Arret suivi demande par $destinataire")
    }

    private fun traiterPosition(context: Context, expediteur: String, message: String) {
        val parts = message.removePrefix("!!").split(",")
        if (parts.size >= 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null) {
                val broadcast = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
                    setPackage(context.packageName)
                    putExtra("sms_message", message)
                    putExtra("sender_number", expediteur)
                }
                context.sendBroadcast(broadcast)
            }
        }
    }

    private fun envoyerSMSInvisible(destinataire: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(destinataire, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(destinataire, null, parts, null, null)
            }
            Log.d("SmsReceiver", "SMS invisible envoye a $destinataire")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Erreur envoi SMS: ${e.message}")
        }
    }
}
