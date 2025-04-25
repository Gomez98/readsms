package com.simplemobiletools.smsmessenger.messaging

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.simplemobiletools.commons.helpers.isSPlus
import com.simplemobiletools.smsmessenger.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import com.simplemobiletools.smsmessenger.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import com.simplemobiletools.smsmessenger.receivers.SendStatusReceiver
import com.simplemobiletools.smsmessenger.receivers.SmsStatusDeliveredReceiver
import com.simplemobiletools.smsmessenger.receivers.SmsStatusSentReceiver
import java.io.File

/** Class that sends chat message via SMS. */
class SmsSender(val app: Application) {

    private val sendMultipartSmsAsSeparateMessages = false

    fun sendMessage(
        subId: Int,
        destination: String,
        body: String,
        serviceCenter: String?,
        requireDeliveryReport: Boolean,
        messageUri: Uri
    ) {
        var dest = destination
        if (body.isEmpty()) throw IllegalArgumentException("SmsSender: empty text message")
        dest = PhoneNumberUtils.stripSeparators(dest)
        if (dest.isEmpty()) throw SmsException(EMPTY_DESTINATION_ADDRESS)

        val smsManager = getSmsManager(subId)
        val messages = smsManager.divideMessage(body)
        if (messages == null || messages.size < 1) throw SmsException(ERROR_SENDING_MESSAGE)

        sendInternal(
            subId, dest, messages, serviceCenter, requireDeliveryReport, messageUri
        )
    }

    private fun sendInternal(
        subId: Int,
        dest: String,
        messages: ArrayList<String>,
        serviceCenter: String?,
        requireDeliveryReport: Boolean,
        messageUri: Uri
    ) {
        val smsManager = getSmsManager(subId)
        val messageCount = messages.size
        val deliveryIntents = ArrayList<PendingIntent?>(messageCount)
        val sentIntents = ArrayList<PendingIntent>(messageCount)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (isSPlus()) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        for (i in 0 until messageCount) {
            val partId = if (messageCount <= 1) 0 else i + 1

            if (requireDeliveryReport && i == messageCount - 1) {
                deliveryIntents.add(
                    PendingIntent.getBroadcast(
                        app,
                        partId,
                        getDeliveredStatusIntent(messageUri, subId),
                        flags
                    )
                )
            } else {
                deliveryIntents.add(null)
            }

            sentIntents.add(
                PendingIntent.getBroadcast(
                    app,
                    partId,
                    getSendStatusIntent(messageUri, subId),
                    flags
                )
            )
        }

        try {
            val context = app.applicationContext

            if (sendMultipartSmsAsSeparateMessages) {
                for (i in 0 until messageCount) {
                    val messagePart = messages[i]
                    smsManager.sendTextMessage(dest, serviceCenter, messagePart, sentIntents[i], deliveryIntents[i])
                }
            } else {
                smsManager.sendMultipartTextMessage(dest, serviceCenter, messages, sentIntents, deliveryIntents)
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_SENDING_MESSAGE, e)
        }
    }

    private fun getSendStatusIntent(requestUri: Uri, subId: Int): Intent {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION, requestUri, app, SmsStatusSentReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        return intent
    }

    private fun getDeliveredStatusIntent(requestUri: Uri, subId: Int): Intent {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION, requestUri, app, SmsStatusDeliveredReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        return intent
    }

    companion object {
        private var instance: SmsSender? = null
        fun getInstance(app: Application): SmsSender {
            if (instance == null) {
                instance = SmsSender(app)
            }
            return instance!!
        }
    }
}
