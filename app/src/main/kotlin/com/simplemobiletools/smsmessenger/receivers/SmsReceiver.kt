package com.simplemobiletools.smsmessenger.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.simplemobiletools.commons.extensions.baseConfig
import com.simplemobiletools.commons.extensions.getMyContactsCursor
import com.simplemobiletools.commons.extensions.isNumberBlocked
import com.simplemobiletools.commons.helpers.SimpleContactsHelper
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.PhoneNumber
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.helpers.refreshMessages
import com.simplemobiletools.smsmessenger.models.Message
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        var address = ""
        var body = ""
        var subject = ""
        var date = 0L
        var threadId = 0L
        var status = Telephony.Sms.STATUS_NONE
        val type = Telephony.Sms.MESSAGE_TYPE_INBOX
        val read = 0
        val subscriptionId = intent.getIntExtra("subscription", -1)

        val privateCursor = context.getMyContactsCursor(false, true)
        ensureBackgroundThread {
            messages.forEach {
                address = it.originatingAddress ?: ""
                subject = it.pseudoSubject
                status = it.status
                body += it.messageBody
                date = System.currentTimeMillis()
                threadId = context.getThreadId(address)
            }

            if (context.baseConfig.blockUnknownNumbers) {
                val simpleContactsHelper = SimpleContactsHelper(context)
                simpleContactsHelper.exists(address, privateCursor) { exists ->
                    if (exists) {
                        handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
                    }
                }
            } else {
                handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
            }
        }
    }

    private fun handleMessage(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int,
        threadId: Long,
        type: Int,
        subscriptionId: Int,
        status: Int
    ) {
        if (body.contains("correctamente")) {
            val datos = extraerDatosMensaje(body)
            if (datos != null) {
                val telefonoUsr = address
                val telefonoFise = address
                enviarAlBackendSap(context, datos, telefonoFise, telefonoUsr)
            }
        }

        if (isMessageFilteredOut(context, body)) return

        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        Handler(Looper.getMainLooper()).post {
            if (!context.isNumberBlocked(address)) {
                val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                ensureBackgroundThread {
                    val newMessageId = context.insertNewSMS(address, subject, body, date, read, threadId, type, subscriptionId)

                    val conversation = context.getConversations(threadId).firstOrNull() ?: return@ensureBackgroundThread

                    try {
                        context.insertOrUpdateConversation(conversation)
                        context.updateUnreadCountBadge(context.conversationsDB.getUnreadConversations())
                    } catch (_: Exception) {}

                    val senderName = context.getNameFromAddress(address, privateCursor)
                    val phoneNumber = PhoneNumber(address, 0, "", address)
                    val participant = SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
                    val participants = arrayListOf(participant)
                    val messageDate = (date / 1000).toInt()

                    val message = Message(
                        newMessageId,
                        body,
                        type,
                        status,
                        participants,
                        messageDate,
                        false,
                        threadId,
                        false,
                        null,
                        address,
                        senderName,
                        photoUri,
                        subscriptionId
                    )

                    context.messagesDB.insertOrUpdate(message)
                    if (context.config.isArchiveAvailable) {
                        context.updateConversationArchivedStatus(threadId, false)
                    }
                    refreshMessages()
                    context.showReceivedMessageNotification(newMessageId, address, body, threadId, bitmap)
                }
            }
        }
    }

    private fun isMessageFilteredOut(context: Context, body: String): Boolean {
        return context.config.blockedKeywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun extraerDatosMensaje(body: String): Map<String, String>? {
        return try {
            val descripcion = body.lines().firstOrNull()?.trim() ?: return null
            val dni = Regex("""DNI:\s*(\d{8})""").find(body)?.groupValues?.get(1) ?: return null
            val cupon = Regex("""Cupon:\s*(\d{13})""").find(body)?.groupValues?.get(1) ?: return null
            val importe = Regex("""Importe:\s*S/\.?\s*(\d+(\.\d{1,2})?)""").find(body)?.groupValues?.get(1) ?: return null

            mapOf(
                "descripcion" to descripcion,
                "dni" to dni,
                "cupon" to cupon,
                "importe" to importe
            )
        } catch (e: Exception) {
            Log.e("EXTRAER_SMS", "Error al extraer datos: ${e.message}")
            null
        }
    }

    private fun enviarAlBackendSap(context: Context, datos: Map<String, String>, telefonoFise: String, telefonoUsr: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("companyDB", "TEST_LLAMAGAS")
                    put("username", "llgservicelayer")
                    put("password", "LLGS3RV1C3L4Y3R")
                    put("dni", datos["dni"])
                    put("cupon", datos["cupon"])
                    put("importe", datos["importe"])
                    put("descripcion", datos["descripcion"])
                    put("telefonoFise", telefonoFise)
                    put("telefonoUsr", telefonoUsr)
                }

                val url = URL("https://sap5.nubeprivada.llamagas.biz/api/sap/registrar-sms")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d("SAP_BACKEND", "Respuesta: $response")
            } catch (e: Exception) {
                Log.e("SAP_BACKEND", "Error al enviar al backend: ${e.message}", e)
            }
        }.start()
    }
}
