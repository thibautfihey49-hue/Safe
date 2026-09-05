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
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            messageBody.append(sms.messageBody)
        }
        
        val message = messageBody.toString().trim()
        Log.d(TAG, "📩 SMS reçu: $message")

        // ✅ TOUT CE QUI COMMENCE PAR !! = RÉPONSE DE POSITION
        if (message.startsWith("!!")) {
            Log.d(TAG, "✅ Commande MySafe détectée — transmission à l'UI !")
            
            // ⚡ DIFFUSER LA RÉPONSE DIRECTEMENT À L'ACTIVITÉ
            val uiIntent = Intent(MySafeAgentService.SMS_RECEIVED)
            uiIntent.setPackage(context?.packageName) // CIBLE NOTRE APPLI SEULEMENT
            uiIntent.putExtra("sms_message", message)
            context?.sendBroadcast(uiIntent)
            
            Log.d(TAG, "📡 Réponse envoyée à l'activité !")
            
            // Essayer de cacher le SMS — si le système l'autorise
            try { abortBroadcast() } catch (e: Exception) {}
        }
    }
}
