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
        
        val message = messageBody.toString()
        
        // ✅ ABORT EN PREMIER — LE SMS N'APPARAÎT JAMAIS DANS LA CONVERSATION !
        if (message.startsWith("!!")) {
            Log.d(TAG, "📩 Commande MySafe interceptée — 100% invisible 🤫")
            abortBroadcast() // ❌ ANNULER AFFICHAGE IMMÉDIATEMENT !
            
            val sender = pdus.firstOrNull()?.let {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(it as ByteArray, bundle.getString("format"))
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(it as ByteArray)
                }
                sms.originatingAddress
            } ?: ""
            
            val serviceIntent = Intent(context, MySafeAgentService::class.java).apply {
                action = MySafeAgentService.ACTION_PROCESS_COMMAND
                putExtra("sender_number", sender)
                putExtra("command", message)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context?.startForegroundService(serviceIntent)
            } else {
                context?.startService(serviceIntent)
            }
        }
    }
}
