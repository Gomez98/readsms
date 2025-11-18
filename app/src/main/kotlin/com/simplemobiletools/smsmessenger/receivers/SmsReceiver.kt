package com.simplemobiletools.smsmessenger.receivers

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.Gson
import com.simplemobiletools.smsmessenger.BuildConfig
import com.simplemobiletools.smsmessenger.databases.MessagesDatabase
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.interfaces.TransactionDao
import com.simplemobiletools.smsmessenger.models.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.HttpURLConnection
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

    // Modelo de validación
    data class MsjValidacion(
        val cupon: String,
        val dni: String,
        val code: Int?,
        val sn: String?
    )

    override fun onReceive(context: Context, intent: Intent) {
        val startTime = System.currentTimeMillis()

        try {
            // Validar acción
            if (Telephony.Sms.Intents.SMS_DELIVER_ACTION != intent.action) {
                Log.w(TAG, "Acción no es SMS_DELIVER: ${intent.action}")
                return
            }

            // Validar contexto
            val appContext = context.applicationContext
            if (appContext !is Application) {
                Log.e(TAG, "Contexto inválido: no es Application")
                return
            }

            // Inicializar componentes
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

    private suspend fun processMessages(context: Context, msgs: Array<android.telephony.SmsMessage>) {
        msgs.forEachIndexed { index, msg ->
            try {
                Log.d(TAG, "Procesando mensaje ${index + 1}/${msgs.size}")
                processSingleMessage(context, msg)
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje individual $index", e)
                // Continuar con el siguiente mensaje
            }
        }
    }

    private suspend fun processSingleMessage(context: Context, msg: android.telephony.SmsMessage) {
        val messageStartTime = System.currentTimeMillis()

        try {
            // Extraer datos del mensaje
            val from = msg.displayOriginatingAddress ?: msg.originatingAddress ?: run {
                Log.w(TAG, "Remitente no disponible")
                return
            }

            val body = msg.displayMessageBody ?: msg.messageBody ?: run {
                Log.w(TAG, "Cuerpo del mensaje no disponible")
                return
            }

            Log.i(TAG, "📨 De: $from, Mensaje: ${body.take(50)}...")

            // Validar longitud del mensaje
            if (body.length > MESSAGE_MAX_LENGTH || body.length < MESSAGE_MIN_LENGTH) {
                Log.w(TAG, "Mensaje con longitud inválida: ${body.length} caracteres")
                return
            }

            // Anti-duplicado: verificar si ya procesamos este mensaje
            val cacheKey = generateCacheKey(from, body)
            if (isRecentlyProcessed(cacheKey)) {
                Log.d(TAG, "Mensaje duplicado ignorado: $from")
                return
            }

            // Guardar en el provider de telefonía
            saveToTelephony(context, from, body, msg.timestampMillis)

            // Procesar lógica FISE
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
        val timestamp = processedMessages[key]
        if (timestamp == null) return false

        val isRecent = System.currentTimeMillis() - timestamp < CACHE_DURATION_MS
        if (!isRecent) {
            processedMessages.remove(key)
        }
        return isRecent
    }

    private fun markAsProcessed(key: String) {
        processedMessages[key] = System.currentTimeMillis()
    }

    private fun saveToTelephony(context: Context, from: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.Inbox.ADDRESS, from)
                put(Telephony.Sms.Inbox.BODY, body)
                put(Telephony.Sms.Inbox.DATE, timestamp)
                put(Telephony.Sms.Inbox.READ, 0)
            }

            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.d(TAG, "💾 Mensaje guardado en telefonía")

            // Notificar a la UI
            org.greenrobot.eventbus.EventBus.getDefault().post(Events.RefreshMessages())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando en telefonía", e)
        }
    }

    private suspend fun processFiseLogic(context: Context, from: String, body: String): Result<Unit> {
        return try {
            Log.d(TAG, "🔍 Procesando lógica FISE")

            // Parsear datos del mensaje
            val (cupon, dni, sn) = parseCuponDni(body)

            if (cupon.isBlank() || dni.isBlank()) {
                Log.w(TAG, "⚠️ No se pudo parsear cupón/DNI correctamente")
                return Result.failure(Exception("Formato inválido"))
            }

            Log.i(TAG, "📋 Datos parseados - Cupón: $cupon, DNI: $dni, SN: ${sn ?: "N/A"}")

            // Buscar dealer asociado
            val dealerInfo = safeApiCall("agentParent") {
                getParentDealer(from)
            }.getOrNull()

            if (dealerInfo == null) {
                Log.w(TAG, "⚠️ No se encontró dealer para: $from")
                return Result.failure(Exception("Dealer no encontrado"))
            }

            val phoneDealer = dealerInfo.U_LLG_DEALER_PHONE
            if (phoneDealer.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Dealer sin teléfono asignado")
                return Result.failure(Exception("Dealer sin teléfono"))
            }

            Log.i(TAG, "🏢 Dealer encontrado: $phoneDealer")

            // Enviar a FISE
            val replyText = "FISE AH02 $dni $cupon"
            return sendToFiseWithRetry(context, phoneDealer, replyText, from, cupon, dni, sn)

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
            // Usamos Semaphore de java.util.concurrent para limitar concurrencia
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

    /**
     * Enviar SMS REAL usando SmsManager, sin pasar por SmsSender.
     */
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

    private suspend fun sendToFiseWithRetry(
        context: Context,
        phoneDealer: String,
        replyText: String,
        from: String,
        cupon: String,
        dni: String,
        sn: String?
    ): Result<Unit> {
        return try {
            Log.i(TAG, "📤 Enviando a FISE: $replyText → $phoneDealer")

            // Enviar SMS al dealer con SmsManager
            sendSms(context, phoneDealer, replyText)

            // Guardar transacción
            val transaction = Transaction(
                driverPhone = from,
                entidad = phoneDealer,
                agentePhone = from,
                // sn se omite en el modelo si no existe en la data class Transaction
                cupon = cupon,
                dni = dni,
                fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                monto = null,
                estado = TxStatus.PENDING.toString(),
                respuesta = null
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
            // Validar datos antes de guardar
            if (transaction.cupon.isBlank() || transaction.dni.isBlank()) {
                Log.w(TAG, "⚠️ Datos inválidos para transacción")
                return
            }

            transactionDao.insertOrUpdate(transaction)
            Log.d(TAG, "💾 Transacción guardada: ${transaction.cupon}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando transacción", e)
        }
    }

    // Parser mejorado con validaciones
    private fun parseCuponDni(body: String): Triple<String, String, String?> {
        return try {
            val cuponRegex = Regex("""(?i)CUPON[:\s]+(\d{6,20})""")
            val dniRegex = Regex("""(?i)DNI[:\s]+(\d{8})""")
            val snRegex = Regex("""(?i)\bS/?N[:\s]+(C\d{8})\b""")

            val cupon = cuponRegex.find(body)?.groupValues?.get(1) ?: ""
            val dni = dniRegex.find(body)?.groupValues?.get(1) ?: ""
            val sn = snRegex.find(body)?.groupValues?.get(1)

            Log.d(TAG, "🔍 Parseo exitoso - Cupón: $cupon, DNI: $dni, SN: $sn")
            Triple(cupon, dni, sn)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parseando mensaje", e)
            Triple("", "", null)
        }
    }

    // Funciones API mejoradas
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

    // Función para procesar respuestas de FISE (cuando recibes la validación)
    suspend fun procesarRespuestaFise(context: Context, from: String, body: String) {
        try {
            Log.i(TAG, "📨 Procesando respuesta FISE de: $from")

            val parsedBody = extraerDatosMensaje(body)
            if (parsedBody == null) {
                Log.e(TAG, "❌ No se pudo parsear respuesta FISE")
                return
            }

            when (val code = parsedBody["code"] as? Int ?: -1) {
                0 -> procesarMensajeValido(context, from, parsedBody)
                10 -> procesarMensajeErrado(parsedBody)
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

            // Buscar transacción pendiente
            val transaction = transactionDao.getTxByCuponAndDni(cupon, dni)
            if (transaction == null) {
                Log.w(TAG, "⚠️ No se encontró transacción PENDING para: $cupon / $dni")
                return
            }

            val driverPhone = transaction.driverPhone
            if (driverPhone.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Teléfono del chofer no disponible")
                return
            }

            // Actualizar transacción
            transactionDao.updateTransaction(
                cupon = cupon,
                dni = dni,
                estado = TxStatus.DELIVERED.toString(),
                monto = importe,
                respuesta = descripcion
            )

            // Enviar respuesta al chofer
            val textoChofer = if (importe != null) {
                "Cupón $cupon validado. Importe por S/ $importe"
            } else {
                "Cupón $cupon validado correctamente."
            }

            Log.i(TAG, "📤 Enviando a chofer $driverPhone: $textoChofer")
            sendSms(context, driverPhone, textoChofer)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje válido", e)
        }
    }

    private fun procesarMensajeErrado(parsedBody: Map<String, Any>) {
        try {
            val rawMessage = parsedBody["raw"] as? String ?: ""
            Log.w(TAG, "⚠️ Mensaje ERRADO de FISE: $rawMessage")

            // TODO: Implementar notificación al chofer de cupón errado

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

            // Buscar transacción
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

            // Notificar al chofer
            val textoChofer = "Cupón $cupon ya fue validado anteriormente"
            Log.i(TAG, "📤 Enviando a chofer $driverPhone: $textoChofer")
            sendSms(context, driverPhone, textoChofer)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje procesado", e)
        }
    }

    // Parser mejorado de mensajes FISE
    private fun extraerDatosMensaje(body: String): Map<String, Any>? {
        return try {
            // Limitar tamaño para evitar OOM
            if (body.length > 500) {
                Log.w(TAG, "Mensaje FISE demasiado largo: ${body.length} caracteres")
                return null
            }

            // Caso ERRADO
            if (Regex("""\bERRADO\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                return mapOf(
                    "code" to 10,
                    "mensaje" to "Vale ERRADO",
                    "raw" to body
                )
            }

            // Caso VALE PROCESADO
            if (Regex("""\bVALE PROCESADO\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                val cupon = body.trim().substringAfterLast(' ')
                val fechaHoraRegex = Regex("""\b(\d{2}/\d{2}/\d{4})\s+([01]?\d|2[0-3]):[0-5]\d\b""")
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

            // Caso válido con Cupon/DNI/Importe
            val descripcion = body.lines().firstOrNull()?.trim() ?: ""
            val dni = Regex("""DNI:\s*(\d{8})""").find(body)?.groupValues?.get(1)
            val cupon = Regex("""CUPON:\s*(\d{10,13})""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)

            val importeRaw = Regex(
                """IMPORTE[:=]?\s*(?:S\s*/\s*\.)?\s*([0-9]{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})|[0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)

            val importe = importeRaw?.let { normNumber(it) } ?: 0.0

            if (dni != null && cupon != null) {
                return mapOf(
                    "code" to 0,
                    "descripcion" to descripcion,
                    "dni" to dni,
                    "cupon" to cupon,
                    "importe" to importe,
                    "raw" to body,
                    "mensaje" to "Generacion FISE"
                )
            }

            // Ningún caso reconocido
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

    // Normalización de números
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
}
