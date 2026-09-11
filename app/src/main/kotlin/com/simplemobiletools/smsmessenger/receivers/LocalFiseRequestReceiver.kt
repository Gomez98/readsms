package com.simplemobiletools.smsmessenger.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LocalFiseRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsReceiver.LOCAL_REQUEST_ACTION) return
        SmsReceiver().processLocalRequest(context.applicationContext, intent, goAsync())
    }
}
