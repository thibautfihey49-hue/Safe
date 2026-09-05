package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class DataSMSReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DataSMSReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        for (pdu in pdus) {
            val msg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }

            val messageText = msg.messageBody ?: continue
            val sender = msg.originatingAddress ?: "?"

            Log.d(TAG, "📩 SMS reçu de $sender : $messageText")

            // ✅ Transmettre à MainActivity via broadcast
            val forwardIntent = Intent(MySafeAgentService.SMS_RECEIVED).apply {
                setPackage(context.packageName)
                putExtra("sms_message", messageText)
                putExtra("sender_number", sender)
            }
            context.sendBroadcast(forwardIntent)
        }
    }
}
