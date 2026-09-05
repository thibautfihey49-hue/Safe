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
        Log.d(TAG, "📩 SMS BRUT reçu de $senderNumber : [$message]")

        // ✅ TOUT CE QUI COMMENCE PAR !! = RÉPONSE DE POSITION
        if (message.startsWith("!!")) {
            Log.d(TAG, "✅ Réponse de position détectée !")
            
            // ⚡ DIFFUSER LA RÉPONSE À L'UI — avec le bon nom d'action !
            val uiIntent = Intent("com.mysafe.mysafe.SMS_RECEIVED")
            uiIntent.setPackage(context?.packageName)
            uiIntent.putExtra("sms_message", message)
            context?.sendBroadcast(uiIntent)
            
            Log.d(TAG, "📡 Réponse envoyée à l'UI: $message")
            
            // Essayer de cacher le SMS
            try { abortBroadcast() } catch (e: Exception) {}
        }
    }
}
