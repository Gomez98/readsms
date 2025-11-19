package com.simplemobiletools.smsmessenger.receivers

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.Gson
import com.simplemobiletools.smsmessenger.databases.MessagesDatabase
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.interfaces.TransactionDao
import com.simplemobiletools.smsmessenger.models.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val URL_PATH = "https://apiconsultas.llamagas.nubeprivada.biz/api"
        private const val MESSAGE_MAX_LENGTH = 500
        private const val MESSAGE_MIN_LENGTH = 10
        private const val PROCESS_TIMEOUT_MS = 30000L
        private const val MAX_CONCURRENT_CALLS = 5
        private const val CACHE_DURATION_MS = 5 * 60 * 1000 // 5 minutos
    }

    private lateinit var transactionDao: TransactionDao
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val apiSemaphore = Semaphore(MAX_CONCURRENT_CALLS)
    private val processedMessages = ConcurrentHashMap<String, Long>()

    data class MsjValidacion(
        val cupon: String,
        val dni: String,
        val code: Int?,
        val sn: String?
    )

    override fun onReceive(context: Context, intent: Intent) {
        val startTime = System.currentTimeMillis()

        try {
            if (Telephony.Sms.Intents.SMS_DELIVER_ACTION != intent.action) {
                Log.w(TAG, "Acción no es SMS_DELIVER: ${intent.action}")
                return
            }

            val appContext = context.applicationContext
            if (appContext !is Application) {
                Log.e(TAG, "Contexto inválido: no es Application")
                return
            }

            transactionDao = MessagesDatabase.getInstance(context).TransactionDao()

            val pendingResult = goAsync()
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (msgs.isNullOrEmpty()) {
                Log.w(TAG, "No hay mensajes para procesar")
                pendingResult.finish()
                return
            }

            Log.i(TAG, "📩 Procesando ${msgs.size} mensaje(s)")

            scope.launch {
                try {
                    withTimeout(PROCESS_TIMEOUT_MS) {
                        processMessages(context, msgs)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "⏰ Timeout procesando mensajes", e)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error crítico procesando mensajes", e)
                } finally {
                    pendingResult.finish()
                    val endTime = System.currentTimeMillis()
                    Log.d(TAG, "✅ SmsReceiver completado en ${endTime - startTime}ms")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error crítico en onReceive", e)
        }
    }

    // 🔴 Reconstruimos el SMS completo (multi-parte)
    private suspend fun processMessages(
        context: Context,
        msgs: Array<android.telephony.SmsMessage>
    ) {
        if (msgs.isEmpty()) return

        val first = msgs[0]
        val from = first.displayOriginatingAddress ?: first.originatingAddress ?: run {
            Log.w(TAG, "Remitente no disponible (multi-part)")
            return
        }

        val fullBody = msgs.joinToString(separator = "") { part ->
            part.displayMessageBody ?: part.messageBody ?: ""
        }

        val timestamp = first.timestampMillis

        Log.d(
            TAG,
            "📦 Mensaje reconstruido (${msgs.size} partes) desde $from: ${fullBody.take(80)}..."
        )

        processSingleMessage(context, from, fullBody, timestamp)
    }

    // Detectar si el mensaje "parece" respuesta FISE por contenido
    private fun looksLikeFiseResponse(body: String): Boolean {
        val upper = body.uppercase(Locale.getDefault())

        // patrones claros de RESPUESTA FISE (no del chofer)
        if (upper.contains("ERRADO")) return true
        if (upper.contains("VALE PROCESADO")) return true
        if (upper.contains("EL CUPON SE PROCESO CORRECTAMENTE")) return true
        if (upper.contains("IMPORTE:")) return true

        // OJO: NO usamos "DNI:" && "CUPON:" porque el chofer también lo manda así
        return false
    }

    private suspend fun processSingleMessage(
        context: Context,
        from: String,
        body: String,
        timestamp: Long
    ) {
        val messageStartTime = System.currentTimeMillis()

        try {
            Log.i(TAG, "📨 De: $from, Mensaje: ${body.take(80)}...")

            if (body.length > MESSAGE_MAX_LENGTH || body.length < MESSAGE_MIN_LENGTH) {
                Log.w(TAG, "Mensaje con longitud inválida: ${body.length} caracteres")
                return
            }

            val cacheKey = generateCacheKey(from, body)
            if (isRecentlyProcessed(cacheKey)) {
                Log.d(TAG, "Mensaje duplicado ignorado: $from")
                return
            }

            // Guardamos SIEMPRE en la bandeja de entrada
            saveIncomingToTelephony(context, from, body, timestamp)

            // Si el contenido parece respuesta FISE → procesar como respuesta
            if (looksLikeFiseResponse(body)) {
                Log.i(TAG, "📨 Mensaje con formato FISE, procesando respuesta...")
                procesarRespuestaFise(context, from, body)
                markAsProcessed(cacheKey)
                return
            }

            // Si no es respuesta FISE → se asume mensaje de chofer / celular autorizado
            val result = processFiseLogic(context, from, body)

            if (result.isSuccess) {
                markAsProcessed(cacheKey)
                Log.i(TAG, "✅ Mensaje procesado exitosamente: $from")
            } else {
                Log.w(TAG, "⚠️ Mensaje procesado con advertencias: $from")
            }

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OutOfMemory procesando mensaje", e)
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje individual", e)
        } finally {
            val messageEndTime = System.currentTimeMillis()
            Log.d(TAG, "⏱️ Tiempo procesamiento mensaje: ${messageEndTime - messageStartTime}ms")
        }
    }

    private fun generateCacheKey(from: String, body: String): String {
        return "${from}_${body.hashCode()}"
    }

    private fun isRecentlyProcessed(key: String): Boolean {
        val timestamp = processedMessages[key] ?: return false
        val isRecent = System.currentTimeMillis() - timestamp < CACHE_DURATION_MS
        if (!isRecent) {
            processedMessages.remove(key)
        }
        return isRecent
    }

    private fun markAsProcessed(key: String) {
        processedMessages[key] = System.currentTimeMillis()
    }

    // 💾 ENTRANTE → INBOX
    private fun saveIncomingToTelephony(
        context: Context,
        from: String,
        body: String,
        timestamp: Long
    ) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.Inbox.ADDRESS, from)
                put(Telephony.Sms.Inbox.BODY, body)
                put(Telephony.Sms.Inbox.DATE, timestamp)
                put(Telephony.Sms.Inbox.READ, 0)
            }

            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.d(TAG, "💾 Mensaje ENTRANTE guardado en telefonía (INBOX)")

            org.greenrobot.eventbus.EventBus.getDefault().post(Events.RefreshMessages())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando entrante en telefonía", e)
        }
    }

    // 💾 SALIENTE → SENT
    private fun saveOutgoingToTelephony(
        context: Context,
        to: String,
        body: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            if (to.isBlank()) {
                Log.w(TAG, "⚠️ No se guarda SMS saliente: número vacío")
                return
            }

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, to)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
            }

            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            Log.d(TAG, "💾 Mensaje SALIENTE guardado en telefonía (SENT) → $to")

            org.greenrobot.eventbus.EventBus.getDefault().post(Events.RefreshMessages())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando saliente en telefonía", e)
        }
    }

    /**
     * Lógica cuando el mensaje es de chofer / celular autorizado.
     * Aquí se obtiene dinámicamente el número FISE/entidad desde la API.
     */
    private suspend fun processFiseLogic(
        context: Context,
        from: String,
        body: String
    ): Result<Unit> {
        return try {
            Log.d(TAG, "🔍 Procesando lógica FISE")

            val (cupon, dni, sn) = parseCuponDni(body)

            if (cupon.isBlank() || dni.isBlank()) {
                Log.w(TAG, "⚠️ No se pudo parsear cupón/DNI correctamente")
                return Result.failure(Exception("Formato inválido"))
            }

            Log.i(TAG, "📋 Datos parseados - Cupón: $cupon, DNI: $dni, SN: ${sn ?: "N/A"}")

            val dealerInfo = safeApiCall("agentParent") {
                getParentDealer(from)
            }.getOrNull()

            if (dealerInfo == null) {
                Log.w(TAG, "⚠️ No se encontró dealer para: $from")
                return Result.failure(Exception("Dealer no encontrado"))
            }

            val phoneDealer = dealerInfo.U_LLG_DEALER_PHONE
            val agentPhone = dealerInfo.U_LLG_AGENT_PHONE  // si tu modelo lo tiene

            if (phoneDealer.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Dealer sin teléfono asignado")
                return Result.failure(Exception("Dealer sin teléfono"))
            }

            Log.i(TAG, "🏢 Dealer encontrado (FISE/Entidad): $phoneDealer")

            val replyText = "FISE AH02 $dni $cupon"
            return sendToFiseWithRetry(
                context = context,
                phoneDealer = phoneDealer,
                replyText = replyText,
                from = from,
                cupon = cupon,
                dni = dni,
                sn = sn,
                agentePhone = agentPhone
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en lógica FISE", e)
            Result.failure(e)
        }
    }

    private suspend fun <T> safeApiCall(
        endpoint: String,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            apiSemaphore.acquire()
            try {
                withTimeout(8000) {
                    Result.success(block())
                }
            } finally {
                apiSemaphore.release()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "⏰ Timeout en API: $endpoint")
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "🔌 Timeout de socket en API: $endpoint")
            Result.failure(e)
        } catch (e: IOException) {
            Log.e(TAG, "🌐 Error de red en API: $endpoint - ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inesperado en API: $endpoint", e)
            Result.failure(e)
        }
    }

    // Enviar SMS real
    private fun sendSms(context: Context, to: String, text: String) {
        try {
            if (to.isBlank()) {
                Log.e(TAG, "❌ Número destino vacío, no se envía SMS")
                return
            }

            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

            Log.i(TAG, "📤 [SmsManager] Enviando SMS a $to: $text")
            smsManager.sendTextMessage(to, null, text, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando SMS a $to con SmsManager", e)
        }
    }

    /**
     * Aquí se envía el SMS a FISE/Entidad y se inserta la transacción en SQLite
     * como PENDING.
     */
    private suspend fun sendToFiseWithRetry(
        context: Context,
        phoneDealer: String,
        replyText: String,
        from: String,
        cupon: String,
        dni: String,
        sn: String?,
        agentePhone: String?
    ): Result<Unit> {
        return try {
            Log.i(TAG, "📤 Enviando a FISE: $replyText → $phoneDealer")

            sendSms(context, phoneDealer, replyText)
            saveOutgoingToTelephony(context, phoneDealer, replyText)

            val transaction = Transaction(
                driverPhone = from,                    // chofer/celular que envió el cupón
                entidad = phoneDealer,                 // número FISE/Entidad (sin +51 normalmente)
                agentePhone = agentePhone ?: from,     // agente autorizado; si no viene, usamos from
                cupon = cupon,
                dni = dni,
                fecha = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date()),
                monto = null,
                estado = TxStatus.PENDING.toString(),
                respuesta = null,
                sn = sn                                   // 👈 se guarda tal cual llegó
            )

            saveTransaction(transaction)
            Log.i(TAG, "✅ Transacción guardada como PENDING")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando a FISE", e)
            Result.failure(e)
        }
    }

    private fun saveTransaction(transaction: Transaction) {
        try {
            if (transaction.cupon.isBlank() || transaction.dni.isBlank()) {
                Log.w(TAG, "⚠️ Datos inválidos para transacción")
                return
            }

            transactionDao.insertOrUpdate(transaction)
            Log.d(TAG, "💾 Transacción guardada: ${transaction.cupon}")

            // 👇 Debug extra: ver qué hay en la tabla 'transactions'
            scope.launch {
                debugPrintLastTransactions()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando transacción", e)
        }
    }

    // 👇 AQUÍ EL PARSEO MEJORADO: SN se manda tal cual llega
    private fun parseCuponDni(body: String): Triple<String, String, String?> {
        return try {
            val cuponRegex = Regex("""(?i)\bCUPON[:\s]+(\d{6,20})\b""")
            val dniRegex   = Regex("""(?i)\bDNI[:\s]+(\d{8})\b""")

            // Acepta: SN: C..., S/N C..., SN C..., SN, C..., etc.
            val snLabelRegex = Regex(
                pattern = """(?i)\bS/?N\b[^\r\n0-9A-Za-z]*(C\d{8,12})""",
                option = RegexOption.IGNORE_CASE
            )

            var sn: String? = snLabelRegex.find(body)?.groupValues?.get(1)

            // Fallback: por si solo viene el código C########### en el mensaje
            if (sn == null) {
                val snSoloRegex = Regex("""\bC\d{8,12}\b""", RegexOption.IGNORE_CASE)
                sn = snSoloRegex.find(body)?.value
            }

            val cupon = cuponRegex.find(body)?.groupValues?.get(1) ?: ""
            val dni   = dniRegex.find(body)?.groupValues?.get(1) ?: ""

            Log.d(TAG, "🔍 Parseo exitoso - Cupón: $cupon, DNI: $dni, SN: $sn")
            Triple(cupon, dni, sn)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parseando mensaje", e)
            Triple("", "", null)
        }
    }

    private suspend fun getParentDealer(phone: String): Agente? {
        val nro = phone.replace("+51", "")
        Log.d(TAG, "🔍 Buscando dealer para: $nro")

        val urlString = "${URL_PATH}/sl/fise/agentParent?phone=$nro"
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
            }

            val code = connection.responseCode
            Log.d(TAG, "📊 Código respuesta dealer: $code")

            if (code == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val gson = Gson()
                val response = gson.fromJson(responseBody, AgenteResponse::class.java)

                if (response.value.isNotEmpty()) {
                    Log.i(TAG, "✅ Dealer encontrado: ${response.value.size} resultado(s)")
                    response.value.firstOrNull()
                } else {
                    Log.w(TAG, "⚠️ No se encontró dealer")
                    null
                }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "❌ Error consultando dealer ($code): $error")
                null
            }

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "⏰ Timeout consultando dealer", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "🌐 Error red consultando dealer", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inesperado consultando dealer", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    // 🔁 Respuesta FISE (mensaje de la entidad/FISE)
    suspend fun procesarRespuestaFise(context: Context, from: String, body: String) {
        try {
            Log.i(TAG, "📨 Procesando respuesta FISE de: $from")

            val parsedBody = extraerDatosMensaje(body)
            if (parsedBody == null) {
                Log.e(TAG, "❌ No se pudo parsear respuesta FISE")
                return
            }

            when (val code = parsedBody["code"] as? Int ?: -1) {
                0  -> procesarMensajeValido(context, from, parsedBody)
                10 -> procesarMensajeErrado(context, from, parsedBody)
                20 -> procesarMensajeProcesado(context, parsedBody)
                else -> Log.w(TAG, "⚠️ Código FISE no reconocido: $code")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando respuesta FISE", e)
        }
    }

    private suspend fun procesarMensajeValido(
        context: Context,
        from: String,
        parsedBody: Map<String, Any>
    ) {
        try {
            val cupon = parsedBody["cupon"] as? String ?: return
            val dni = parsedBody["dni"] as? String ?: return
            val importe = parsedBody["importe"] as? Double
            val descripcion = parsedBody["descripcion"] as? String ?: ""

            Log.i(TAG, "✅ Cupón válido: $cupon, Importe: $importe")

            // 1) Intentamos buscar por CUPÓN + DNI (lo ideal)
            var transaction = transactionDao.getTxByCuponAndDni(cupon, dni)

            // 2) Si no se encuentra, hacemos fallback a buscar solo por CUPÓN
            if (transaction == null) {
                Log.w(
                    TAG,
                    "⚠️ No se encontró transacción por cupón+DNI. Intentando solo por cupón: $cupon"
                )
                transaction = transactionDao.getTxByCuponOnly(cupon)
            }

            // 3) Si sigue sin encontrarse, ya no podemos hacer nada
            if (transaction == null) {
                Log.w(
                    TAG,
                    "⚠️ No se encontró transacción asociada para: $cupon / $dni (ni por cupón ni por cupón+DNI)"
                )
                return
            }

            val driverPhone = transaction.driverPhone
            if (driverPhone.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Teléfono del chofer no disponible")
                return
            }

            val agentePhone = transaction.agentePhone ?: driverPhone

            // 4) Enviar registro a SAP B1
            val agente = FISE_SMS(
                U_fise_numero = from,              // número FISE que respondió
                U_usr_numero = agentePhone,        // número del agente/supervisor
                U_usr_dni = dni,
                U_fise_codigo = cupon,
                U_importe = importe,
                U_usr_chofer = driverPhone,        // número del chofer original
                U_descripcion = descripcion,
                U_LLG_FISE_SN = transaction.sn     // 👈 SN tal cual se guardó en la TX
            )

            val sapSentSuccessfully = enviarBackendSap2(agente)
            if (!sapSentSuccessfully) {
                Log.e(TAG, "❌ Falló el envío al backend SAP después de $MAX_RETRIES intentos.")
            }

            // 5) Actualizamos la transacción a DELIVERED en SQLite
            transactionDao.updateTransaction(
                cupon = cupon,
                dni = dni,
                estado = TxStatus.DELIVERED.toString(),
                monto = importe,
                respuesta = descripcion
            )

            // 6) Armamos el SMS para el chofer
            val textoChofer = if (importe != null) {
                "Cupón $cupon validado. Importe por S/ $importe"
            } else {
                "Cupón $cupon validado correctamente."
            }

            Log.i(TAG, "📤 Enviando a chofer $driverPhone: $textoChofer")
            sendSms(context, driverPhone, textoChofer)
            saveOutgoingToTelephony(context, driverPhone, textoChofer)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje válido", e)
        }
    }

    // ⚠️ cuando FISE dice "DOC.BENEF. O VALE ERRADO" o similar
    private suspend fun procesarMensajeErrado(
        context: Context,
        from: String,
        parsedBody: Map<String, Any>
    ) {
        try {
            val rawMessage = (parsedBody["raw"] as? String)
                ?.ifBlank { "DOC.BENEF. O VALE ERRADO" }
                ?: "DOC.BENEF. O VALE ERRADO"

            Log.w(TAG, "⚠️ Mensaje ERRADO de FISE: $rawMessage")

            // El 'from' es el número FISE (ej: +51970115159)
            // En la BD guardamos entidad = 970115159 (sin +51)
            val entidadKey = from.removePrefix("+51")

            val tx = try {
                val all = transactionDao.debugGetLastTransactions()
                all.firstOrNull { t ->
                    t.estado == TxStatus.PENDING.toString() &&
                        t.entidad?.endsWith(entidadKey) == true
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error buscando transacción PENDING para entidad=$entidadKey", e)
                null
            }

            if (tx == null) {
                Log.w(TAG, "⚠️ No se encontró transacción PENDING para entidad/FISE=$entidadKey")
                return
            }

            val driverPhone = tx.driverPhone
            if (driverPhone.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Teléfono del chofer vacío en transacción ERRADA")
                return
            }

            // Marcamos la transacción como FAILED con el mensaje de FISE
            try {
                transactionDao.updateTransaction(
                    cupon = tx.cupon,
                    dni = tx.dni,
                    estado = TxStatus.FAILED.toString(),
                    monto = null,
                    respuesta = rawMessage
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error actualizando transacción a FAILED", e)
            }

            // Enviar al chofer EXACTAMENTE el mismo texto que mandó FISE
            Log.i(TAG, "📤 Enviando al chofer $driverPhone el mensaje ERRADO de FISE")
            sendSms(context, driverPhone, rawMessage)
            saveOutgoingToTelephony(context, driverPhone, rawMessage)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje errado", e)
        }
    }

    private suspend fun procesarMensajeProcesado(
        context: Context,
        parsedBody: Map<String, Any>
    ) {
        try {
            val cupon = parsedBody["cupon"] as? String ?: return
            val fecha = parsedBody["fecha"] as? String
            val hora = parsedBody["hora"] as? String

            Log.i(TAG, "📋 Cupón ya procesado: $cupon (Fecha: $fecha, Hora: $hora)")

            val transaction = transactionDao.getTxByCuponOnly(cupon)
            if (transaction == null) {
                Log.w(TAG, "⚠️ No se encontró transacción para cupón procesado: $cupon")
                return
            }

            val driverPhone = transaction.driverPhone
            if (driverPhone.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Teléfono del chofer no disponible")
                return
            }

            // mensaje que pediste
            val textoChofer = "Cupón $cupon ya fue validado"

            Log.i(TAG, "📤 Enviando a chofer $driverPhone: $textoChofer")
            sendSms(context, driverPhone, textoChofer)
            saveOutgoingToTelephony(context, driverPhone, textoChofer)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje procesado", e)
        }
    }

    // Parser respuesta FISE (mensaje completo)
    private fun extraerDatosMensaje(body: String): Map<String, Any>? {
        return try {
            if (body.length > 500) {
                Log.w(TAG, "Mensaje FISE demasiado largo: ${body.length} caracteres")
                return null
            }

            val upper = body.uppercase(Locale.getDefault())

            // ERRADO
            if (upper.contains("ERRADO")) {
                return mapOf(
                    "code" to 10,
                    "mensaje" to "Vale ERRADO",
                    "raw" to body
                )
            }

            // VALE PROCESADO
            if (upper.contains("VALE PROCESADO")) {
                val cupon = body.trim().substringAfterLast(' ')
                val fechaHoraRegex =
                    Regex("""\b(\d{2}/\d{2}/\d{4})\s+([01]?\d|2[0-3]):[0-5]\d\b""")
                val match = fechaHoraRegex.find(body)
                val fecha = match?.groupValues?.get(1)
                val hora = match?.groupValues?.get(2)

                return mapOf(
                    "code" to 20,
                    "mensaje" to "VALE PROCESADO",
                    "fecha" to (fecha ?: ""),
                    "hora" to (hora ?: ""),
                    "cupon" to cupon,
                    "raw" to body
                )
            }

            // Caso válido típico FISE:
            // El cupon se proceso correctamente.
            // DNI: 74944387
            // CUPON: 12345678999
            // IMPORTE: S/. 60
            val descripcion = body.lines().firstOrNull()?.trim() ?: ""

            val dni = Regex(
                """DNI[:\s]+(\d{8})""",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)

            val cupon = Regex(
                """CUPON[:\s]*([0-9]{6,20})""",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)

            val importeRaw = Regex(
                """IMPORTE[:=]?\s*(?:S\s*/\s*\.?)?\s*([0-9]{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})|[0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)

            val importe = importeRaw?.let { normNumber(it) }

            if (dni != null && cupon != null) {
                return mapOf(
                    "code" to 0,
                    "descripcion" to descripcion,
                    "dni" to dni,
                    "cupon" to cupon,
                    "importe" to (importe ?: 0.0),
                    "raw" to body,
                    "mensaje" to "Generacion FISE"
                )
            }

            mapOf(
                "code" to -1,
                "mensaje" to "No se pudo interpretar",
                "raw" to body
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al extraer datos del mensaje FISE", e)
            null
        }
    }

    private fun normNumber(s: String): Double {
        return try {
            val lastDot = s.lastIndexOf('.')
            val lastComma = s.lastIndexOf(',')

            val decimalSep = when {
                lastDot == -1 && lastComma == -1 -> null
                lastDot > lastComma -> '.'
                else -> ','
            }

            val clean = if (decimalSep == null) {
                s.filter { it.isDigit() }
            } else {
                val withoutThousands = s.filter { it.isDigit() || it == '.' || it == ',' }
                    .replace(if (decimalSep == '.') "," else ".", "")
                withoutThousands.replace(decimalSep, '.')
            }

            clean.toDoubleOrNull() ?: 0.0

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error normalizando número: $s", e)
            0.0
        }
    }

    // 👇 envío al backend SAP para registrar en @ALLG_FISE_SMS
    private suspend fun enviarBackendSap2(agente: FISE_SMS): Boolean = withContext(Dispatchers.IO) {
        var success = false
        var retries = 0
        val startTime = System.currentTimeMillis()

        while (!success && retries < MAX_RETRIES) {
            try {
                Log.d(TAG, "Iniciando enviarBackendSap2, intento ${retries + 1}")
                val gson = Gson()
                val jsonBody = gson.toJson(agente)
                Log.d(TAG, "Cuerpo JSON a enviar: $jsonBody")

                val url = URL("${URL_PATH}/sl/fise/registrar-sms")
                val connection = url.openConnection() as HttpURLConnection
                Log.d(TAG, "Conexión abierta a la URL: $url")

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }
                Log.d(TAG, "Propiedades de la conexión establecidas")

                connection.outputStream.use {
                    it.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                Log.d(TAG, "Cuerpo de la solicitud enviado")

                val responseCode = connection.responseCode
                Log.d(TAG, "Código de respuesta recibido: $responseCode")

                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Sin respuesta"
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "✅ Backend SAP ($responseCode): $response")
                    success = true
                } else {
                    Log.e(TAG, "❌ Error al enviar al backend SAP ($responseCode): $response")
                    retries++
                    if (retries < MAX_RETRIES) {
                        Log.w(TAG, "🔄 Reintentando envío al backend SAP (Intento $retries/$MAX_RETRIES)...")
                        delay(RETRY_DELAY_MS)
                    }
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al enviar al backend SAP: ${e.message}", e)
                retries++
                if (retries < MAX_RETRIES) {
                    Log.w(TAG, "🔄 Reintentando envío al backend SAP (Intento $retries/$MAX_RETRIES)...")
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        val endTime = System.currentTimeMillis()
        Log.d(TAG, "enviarBackendSap2 finalizado en ${endTime - startTime} ms")
        return@withContext success
    }

    // 👇 debug para ver lo que hay en la tabla 'transactions'
    private fun debugPrintLastTransactions() {
        try {
            val list = transactionDao.debugGetLastTransactions()

            Log.d(TAG, "================ TRANSACCIONES EN BD ================")
            if (list.isEmpty()) {
                Log.d(TAG, "📭 No hay transacciones en la tabla 'transactions'")
            } else {
                list.forEach { tx ->
                    Log.d(
                        TAG,
                        "TX -> cupon=${tx.cupon}, dni=${tx.dni}, estado=${tx.estado}, " +
                            "driver=${tx.driverPhone}, entidad=${tx.entidad}, fecha=${tx.fecha}, monto=${tx.monto}, sn=${tx.sn}"
                    )
                }
            }
            Log.d(TAG, "=====================================================")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en debugPrintLastTransactions", e)
        }
    }
}
