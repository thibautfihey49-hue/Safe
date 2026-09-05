package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MySafe-SMS"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        
        val messageBody = StringBuilder()
        var senderNumber = ""
        
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            messageBody.append(sms.messageBody)
            if (senderNumber.isEmpty()) senderNumber = sms.originatingAddress ?: ""
        }
        
        val message = messageBody.toString().trim()
        Log.d(TAG, "📩 SMS BRUT reçu de [$senderNumber] : [$message]")

        // ✅ TOUT CE QUI COMMENCE PAR !! = COMMANDE/RÉPONSE
        if (message.startsWith("!!")) {
            Log.d(TAG, "✅ Message de position détecté !")
            
            // ⚡ ENVOYER AU SERVICE AVEC L'EXPÉDITEUR !
            val svcIntent = Intent(context, MySafeAgentService::class.java).apply {
                action = MySafeAgentService.SMS_RECEIVED
                putExtra("sms_message", message)
                putExtra("sender_number", senderNumber)  // ✅ AJOUTÉ !
            }
            context?.startForegroundService(svcIntent)
            
            // ⚡ ENVOYER À L'UI AUSSI
            val uiIntent = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
                setPackage(context?.packageName)
                putExtra("sms_message", message)
            }
            context?.sendBroadcast(uiIntent)
            
            Log.d(TAG, "📡 Message transmis au service + UI")
            
            try { abortBroadcast() } catch (e: Exception) {}
        }
    }
}
