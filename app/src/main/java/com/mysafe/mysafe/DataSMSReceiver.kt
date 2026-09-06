package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

class DataSMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 🔴 EN PREMIER — Intercepter le SMS pour qu'il n'apparaisse PAS dans la messagerie
        abortBroadcast()

        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.action)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val messageComplet = StringBuilder()
        var numeroExpediteur = ""

        for (msg in messages) {
            messageComplet.append(msg.messageBody)
            if (numeroExpediteur.isEmpty()) numeroExpediteur = msg.originatingAddress ?: ""
        }

        val message = messageComplet.toString().trim()
        if (!message.startsWith("!!")) return

        Log.d("DataSMSReceiver", "SMS invisible recu de $numeroExpediteur : $message")

        // Envoyer a l'interface
        val broadcast = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
            setPackage(context.packageName)
            putExtra("sms_message", message)
            putExtra("sender_number", numeroExpediteur)
        }
        context.sendBroadcast(broadcast)
    }
}
