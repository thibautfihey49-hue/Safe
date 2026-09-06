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
        var myPhoneNumber: String? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        
        val body = StringBuilder(); var sender = ""
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu as ByteArray)
            body.append(sms.messageBody)
            if (sender.isEmpty()) sender = sms.originatingAddress ?: ""
        }
        
        val msg = body.toString().trim()
        Log.d(TAG, "📩 SMS de [$sender] : [$msg]")

        if (myPhoneNumber != null && normalize(sender) == normalize(myPhoneNumber)) {
            Log.d(TAG, "🚫 Message de moi-même — ignoré !")
            return
        }

        if (msg.startsWith("!!")) {
            val svc = Intent(context, MySafeAgentService::class.java).apply {
                action = MySafeAgentService.SMS_RECEIVED
                putExtra("sms_message", msg)
                putExtra("sender_number", sender)
            }
            context?.startForegroundService(svc)
            val ui = Intent("com.mysafe.mysafe.SMS_RECEIVED").apply {
                setPackage(context?.packageName)
                putExtra("sms_message", msg)
            }
            context?.sendBroadcast(ui)
            try { abortBroadcast() } catch (_: Exception) {}
        }
    }

    private fun normalize(s: String?) = s?.replace("\\s".toRegex(), "")?.replace("-", "")?.let {
        if (it.startsWith("0") && it.length == 10) "+33${it.substring(1)}" else it
    } ?: ""
}
