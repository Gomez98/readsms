package com.simplemobiletools.smsmessenger.receivers

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.simplemobiletools.smsmessenger.BuildConfig
import com.simplemobiletools.smsmessenger.databases.MessagesDatabase
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.interfaces.TransactionDao
import com.simplemobiletools.smsmessenger.messaging.SmsSender
import com.simplemobiletools.smsmessenger.models.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsDeliverReceiver"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val URL_PATH = "https://apiconsultas.llamagas.nubeprivada.biz/api"
    }

    private lateinit var smsSender: SmsSender
    private lateinit var transactionDao: TransactionDao
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    override fun onReceive(context: Context, intent: Intent) {
        transactionDao = MessagesDatabase.getInstance(context).TransactionDao()
        smsSender = SmsSender.getInstance(app = context.applicationContext as Application)

        if (Telephony.Sms.Intents.SMS_DELIVER_ACTION != intent.action) {
            return
        }

        val pendingResult = goAsync()
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        Log.i(TAG, "📩 SMS_DELIVER recibido (${msgs?.size ?: 0})")

        if (msgs.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ Ningún mensaje recibido")
            pendingResult.finish()
            return
        }

        scope.launch {
            try {
                msgs.forEach { msg ->
                    val from = msg.displayOriginatingAddress ?: msg.originatingAddress ?: ""
                    val body = msg.displayMessageBody ?: msg.messageBody ?: ""
                    Log.i(TAG, "📩 from=$from body=$body")

                    // Verificar si es mensaje QUE RECIBIMOS ES DE alguien FISE
                   val agentes = consultarphone(from)
//                    val dbHelp = LlamagasApp.databaseHelper
                    if (!agentes.isNullOrEmpty()) {
                        val agente = agentes.first()

                        if (!agente.U_LLG_DEALER_PHONE.isNullOrEmpty()) {
                            Log.i(TAG, "📩 dealer=$agente.U_LLG_DEALER_PHONE")
                            // Es un agente FISE, procesamos el mensaje de respuesta de la entidad
                            procesarMensajeFise(context, from, body)
                        } else if (!agente.U_LLG_AGENT_PHONE.isNullOrEmpty()) {
                            Log.i(TAG, "📩 user=$agente.U_LLG_AGENT_PHONE")
                            // No es un agente FISE, se asume que es un chofer enviando un cupón
                            procesarMensajeChofer(context, from, body)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun procesarMensajeFise(
        context: Context,
        from: String,
        body: String
    ) {
        Log.i(TAG, "📨 Procesando mensaje de FISE")

        try {
            //            VALIDAMOS QUE TIPO DE STRING ES Y REVISAMOS
            val parsedBody = extraerDatosMensaje(body)

            if (parsedBody == null) {
                Log.e(TAG, "❌ Error al parsear mensaje de FISE: $body")
                return
            }

            val code = parsedBody["code"] as? Int ?: -1
            Log.i(TAG, "codigo status $code")
            when (code) {
                0 -> procesarMensajeValido(context, from, parsedBody)
                10 ->  procesarMensajeErrado(parsedBody)
                20 ->  procesarMensajeProcesado(parsedBody)

                else -> {
                    Log.w(TAG, "⚠️ Mensaje no reconocido: ${parsedBody["mensaje"]}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando mensaje FISE", e)
        }
    }

    //    RESPUESTA FINAL PARA EL CHOFER O DISTRIBUIDOR
    private suspend fun procesarMensajeChofer(
        context: Context,
        from: String,
        body: String
    ) {
        // Buscar dealer asociado al chofer en servicio API
        val dealerInfo =
            getParentDealer(from)

        val phoneDealer = dealerInfo?.U_LLG_DEALER_PHONE
        val phoneNumber = dealerInfo?.U_LLG_AGENT_PHONE // EL NUMERO DEL SUPERVISOR QUE ENVIA EL SMS A LA ENTIDAD FISE

        Log.i(TAG, "🔎 Dealer encontrado: $phoneDealer")

        if (phoneDealer.isNullOrBlank()) {
            Log.w(TAG, "⚠️ No hay dealer asociado a $from, no se responde")
            return
        }

        if (phoneNumber.isNullOrBlank()) {
            Log.w(TAG, "⚠️ phoneNumber es null para dealer $phoneDealer")
            return
        }

        // Parsear CUPON y DNI del mensaje del chofer
        val (cupon, dni) = parseCuponDni(body)

        if (cupon.isNullOrBlank() || dni.isNullOrBlank()) {
            Log.w(TAG, "⚠️ No se pudo parsear CUPON/DNI en body='$body'")
            return
        }

        // Enviar auto-respuesta a FISE
        val replyText = "FISE AH02 $dni $cupon"
        Log.i(TAG, "📤 Se envía a $phoneDealer: $replyText")
        try {
            smsSender.sendMessage(0, phoneDealer, replyText, null, false, Uri.EMPTY)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando SMS a $phoneDealer", e)
        }
//        // Persistir transacción como PENDING en SQLITE
        val transaction = Transaction(
            driverPhone = from,
            entidad = phoneDealer,
            agentePhone = phoneNumber,
            cupon = cupon,
            dni = dni,
            fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            monto = null,
            estado = TxStatus.PENDING.toString(),
            respuesta = null
        )
        transactionDao.insertOrUpdate(transaction)
    }
    private suspend fun procesarMensajeValido(
        context: Context,
        from: String,
        parsedBody: Map<String, Any>
    ) {
        val cupon = parsedBody["cupon"] as? String
        val dni = parsedBody["dni"] as? String
        val importe = parsedBody["importe"] as? Double
        val descripcion = parsedBody["descripcion"] as? String

        if (cupon.isNullOrBlank() || dni.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Cupón o DNI vacío en mensaje válido")
            return
        }

        // Buscar transacción existente
        val transaction = transactionDao.getTxByCuponAndDni(cupon, dni)

        if (transaction == null) {
            Log.w(TAG, "⚠️ No se encontró transacción PENDING para cupón=$cupon dni=$dni en la última hora.")
            return
        }

        val agentePhone = transaction.agentePhone
        val driver_phone = transaction.driverPhone
        if (agentePhone.isNullOrBlank()) {
            Log.w(TAG, "⚠️ agente_phone vacío en la transacción de BD")
            return
        }

        // Enviar al backend SAP (en background)
        val agente = FISE_SMS(
            U_usr_dni = dni,
            U_fise_codigo = cupon,
            U_importe = importe,
            U_descripcion = descripcion,
            U_fise_numero = from,
            U_usr_numero = agentePhone,
            U_usr_chofer = driver_phone
        )
        val sapSentSuccessfully = enviarBackendSap2(agente)
        if (!sapSentSuccessfully) {
            Log.e(TAG, "❌ Fallo el envío al backend SAP después de $MAX_RETRIES intentos.")
            // TODO: Implementar lógica para notificar al usuario o manejar el fallo persistente
            // Por ejemplo, enviar un SMS al agente informando del fallo.
            // sendAutoReply(context, agentePhone, "Error: No se pudo procesar su solicitud. Intente de nuevo más tarde.")
        }

        // Actualizar BD Sqlite
        transactionDao.updateTransaction(
            cupon = cupon,
            dni = dni,
            estado = TxStatus.DELIVERED.toString(), // ENVIADO A SAP
            monto = importe,
            respuesta = descripcion
        )


        // Enviar SMS al agente usando SmsSender
        val textoChofer = if (importe != null) {
            "Cupón $cupon validado. Importe por S/ $importe"
        } else {
            "Cupón $cupon validado correctamente."
        }

        Log.i(TAG, "📤 Enviando a agente $driver_phone: $textoChofer")
        try {
            // Asumiendo subId = 0 y Uri.EMPTY para el mensaje de respuesta
            smsSender.sendMessage(0, driver_phone.toString(), textoChofer, null, false, Uri.EMPTY)
            // Aquí podrías emitir un SmsEvent si es necesario, similar a sendAutoReply
            // SmsBus.emit(SmsEvent(from = driver_phone, body = textoChofer, timestamp = System.currentTimeMillis(), direction = SmsDirection.OUT, status = SmsStatus.SENT))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando SMS a $driver_phone con SmsSender", e)
        }
    }

//    falta validar segunda respuesta
private fun procesarMensajeErrado(  parsedBody: Map<String, Any>) {
    Log.w(TAG, "⚠️ Vale ERRADO recibido: ${parsedBody["raw"]}")
    // TODO: Implementar lógica para manejar vales errados
    // Por ejemplo: actualizar estado en BD, notificar al agente, etc.

    // Persistir transacción como PENDING en SQLITE
//    val transaction = Transaction(
//        cupon = "0",
//        dni = "0",
//        estado = TxStatus.FAILED.toString(),
//        monto = 0.00,
//        respuesta = parsedBody["raw"] as? String
//    )
//
//    transactionDao.updateTransaction(transaction)

    try {
        // Asumiendo subId = 0 y Uri.EMPTY para el mensaje de respuesta
//        smsSender.sendMessage(0, driver_phone.toString(), textoChofer, null, false, Uri.EMPTY)
        // Aquí podrías emitir un SmsEvent si es necesario, similar a sendAutoReply
        // SmsBus.emit(SmsEvent(from = driver_phone, body = textoChofer, timestamp = System.currentTimeMillis(), direction = SmsDirection.OUT, status = SmsStatus.SENT))
    } catch (e: Exception) {
        Log.e(TAG, "❌ Error enviando SMS con SmsSender", e)
    }
}
    //falta revisar
    private fun procesarMensajeProcesado(  parsedBody: Map<String, Any>) {
        Log.i(TAG, "✅ Vale PROCESADO recibido: ${parsedBody["raw"]}")
        val cupon = parsedBody["cupon"] as? String
        val fecha = parsedBody["fecha"] as? String
        val hora = parsedBody["hora"] as? String

        // TODO: Actualizar estado de la transacción en BD si es necesario
        Log.i(TAG, "   Cupón: $cupon, Fecha: $fecha, Hora: $hora")


        val transaction = cupon?.let { transactionDao.getTxByCuponOnly(it) }

        if (transaction == null) {
            Log.w(TAG, "⚠️ No se encontró transacción PENDING para cupón=$cupon dni en la última hora.")
            return
        }

        val agentePhone = transaction.agentePhone
        val driver_phone = transaction.driverPhone
        if (agentePhone.isNullOrBlank()) {
            Log.w(TAG, "⚠️ agente_phone vacío en la transacción de BD")
            return
        }


        // Enviar SMS al agente usando SmsSender
//        if (t)
        val textoChofer = if (!transaction.dni.isNullOrBlank()) { "Cupón $cupon ya fue validado"} else {"Probar otro cupon"
        }

        Log.i(TAG, "📤 Enviando a agente $driver_phone: $textoChofer")

        try {
            // Asumiendo subId = 0 y Uri.EMPTY para el mensaje de respuesta
            smsSender.sendMessage(0, driver_phone.toString(), textoChofer, null, false, Uri.EMPTY)
            // Aquí podrías emitir un SmsEvent si es necesario, similar a sendAutoReply
            // SmsBus.emit(SmsEvent(from = driver_phone, body = textoChofer, timestamp = System.currentTimeMillis(), direction = SmsDirection.OUT, status = SmsStatus.SENT))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando SMS a $driver_phone con SmsSender", e)
        }

    }
    //    PARSEAR DATOS DE ENTRADA
    private fun parseCuponDni(body: String): Pair<String?, String?> {
        val cuponRegex = Regex("""(?i)CUPON[: ]+(\d{6,20})""")
        val dniRegex = Regex("""(?i)DNI[: ]+(\d{8})""")

        val cupon = cuponRegex.find(body)?.groupValues?.get(1)
        val dni = dniRegex.find(body)?.groupValues?.get(1)

        return cupon to dni
    }

    //    mensaje si es de contexto fise
    private fun extraerDatosMensaje(body: String): Map<String, Any>? {
        return try {
            // Caso error: ERRADO
            if (Regex("""\bERRADO\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                return mapOf(
                    "code" to 10,
                    "mensaje" to "Vale ERRADO",
                    "raw" to body
                )
            }

            // Caso procesado POR LA ENTIDAD FISE

            if (Regex("""\bVALE PROCESADO\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                val cupon = body.trim().substringAfterLast(' ')
                //                val cupon = Regex("CUPON:\s*(\d{10,13})", RegexOption.IGNORE_CASE)
                //                    .find(body)?.groupValues?.get(1)
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
                """IMPORTE[:=]?\s*(?:S\s*/\s*\.?\s*)?\s*([0-9]{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})|[0-9]+)""",
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
            Log.e(TAG, "❌ Error al extraer datos: ${e.message}", e)
            null
        }
    }

    private suspend fun enviarBackendSap2(agente: FISE_SMS): Boolean = withContext(Dispatchers.IO) {
        var success = false
        var retries = 0
        val startTime = System.currentTimeMillis()
        while (!success && retries < MAX_RETRIES) {
            try {
                Log.d(TAG, "Iniciando enviarAlBackendSap, intento ${retries + 1}")
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
        Log.d(TAG, "enviarAlBackendSap finalizado en ${endTime - startTime} ms")
        return@withContext success
    }

    private suspend fun consultarphone(phone: String): List<Agente>? {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Iniciando consultarphone para el teléfono: $phone")
            val nro = phone.replace("+51", "")
            val filter = "?phone=$nro"
            val urlString = "${URL_PATH}/sl/fise/allAgent$filter"
            Log.d(TAG, "URL de la API a consultar: $urlString")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            Log.d(TAG, "Conexión abierta a la URL")

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
                doInput = true
            }
            Log.d(TAG, "Propiedades de la conexión establecidas")

            val code = connection.responseCode
            Log.d(TAG, "Código de respuesta recibido: $code")

            if (code == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "✅ Consulta FISE ($code): $responseBody")
                val gson = Gson()
                val response = gson.fromJson(responseBody, AgenteResponse::class.java)
                return if (response.value.isNotEmpty()) {
                    Log.i(TAG, "✅ ${response.value.size} Agente(s) FISE encontrado(s) para phone=$nro")
                    response.value
                } else {
                    Log.w(TAG, "⚠️ No se encontraron agentes FISE para phone=$nro")
                    null
                }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "❌ Error en consulta FISE ($code): $errorBody")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al consultar phone en backend SAP: ${e.message}", e)
            return null
        } finally {
            val endTime = System.currentTimeMillis()
            Log.d(TAG, "consultarphone finalizado en ${endTime - startTime} ms")
            connection?.disconnect()
            Log.d(TAG, "Conexión cerrada")
        }
    }
    private suspend fun getParentDealer(phone: String): Agente?{
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Iniciando getParentDealer para el teléfono: $phone")
            val nro = phone.replace("+51", "")
            val filter = "?phone=$nro"
            val urlString = "$URL_PATH/sl/fise/agentParent$filter"
            Log.d(TAG, "URL de la API a consultar: $urlString")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            Log.d(TAG, "Conexión abierta a la URL")

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
                doInput = true
            }
            Log.d(TAG, "Propiedades de la conexión establecidas")

            val code = connection.responseCode
            Log.d(TAG, "Código de respuesta recibido: $code")

            if (code == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "✅ Consulta FISE ($code): $responseBody")
                val gson = Gson()
                val response = gson.fromJson(responseBody, AgenteResponse::class.java)
                return if (response.value.isNotEmpty()) {
                    Log.i(TAG, "✅ ${response.value.size} Agente(s) FISE encontrado(s) para phone=$nro")
                    response.value.firstOrNull()
                } else {
                    Log.w(TAG, "⚠️ No se encontraron agentes FISE para phone=$nro")
                    null
                }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "❌ Error en consulta FISE ($code): $errorBody")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al consultar phone en backend SAP: ${e.message}", e)
            return null
        } finally {
            val endTime = System.currentTimeMillis()
            Log.d(TAG, "getParentDealer finalizado en ${endTime - startTime} ms")
            connection?.disconnect()
            Log.d(TAG, "Conexión cerrada")
        }
    }

    /**
     * Normaliza números con '.' y ',':
     * - Si contiene ambos, el último separador se asume decimal.
     * - Si solo contiene ',', se toma como decimal.
     * - Si solo contiene '.', se toma como decimal.
     * - Se remueven separadores de miles.
     */
    private fun normNumber(s: String): Double {
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimalSep =
            when {
                lastDot == -1 && lastComma == -1 -> null
                lastDot > lastComma -> '.'
                else -> ','
            }

        val clean = if (decimalSep == null) {
            s.filter { it.isDigit() }
        } else {
            val withoutThousands = s.filter { it.isDigit() || it == '.' || it == ',' }
                .replace(if (decimalSep == '.') "," else ".", "") // quita el separador “no decimal”
            withoutThousands.replace(decimalSep, '.')
        }
        return clean.toDoubleOrNull() ?: 0.0
    }
}
