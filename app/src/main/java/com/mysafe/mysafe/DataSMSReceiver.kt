package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage

class DataSMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        if ("android.provider.Telephony.SMS_RECEIVED" != intent.action) return

        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        val messages = pdus.map { pdu ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            else SmsMessage.createFromPdu(pdu as ByteArray)
        }

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody }.trim()

        if (!body.startsWith("!!")) return

        abortBroadcast()

        // Envoyer au service pour traitement + réponse
        val svc = Intent(context, MySafeAgentService::class.java).apply {
            action = MySafeAgentService.ACTION_PROCESS_COMMAND
            putExtra("sender_number", sender)
            putExtra("command", body)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
