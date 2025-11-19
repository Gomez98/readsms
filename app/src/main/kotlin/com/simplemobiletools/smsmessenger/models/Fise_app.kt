package com.simplemobiletools.smsmessenger.models

import com.google.gson.annotations.SerializedName

data class AgenteResponse(
    val value: List<Agente>
)

/**
 * Representa la estructura de datos para un Agente,
 * utilizada para la serialización desde la API FISE (consulta de agentes).
 */
data class Agente(

    @SerializedName("Code")
    val Code: String?,

    @SerializedName("U_LLG_AGENT_PHONE")
    val U_LLG_AGENT_PHONE: String?,

    @SerializedName("U_LLG_AGENT_IMEI")
    val U_LLG_AGENT_IMEI: String?,

    @SerializedName("U_LLG_AGENT_NAME")
    val U_LLG_AGENT_NAME: String?,

    @SerializedName("U_LLG_DEALER_PHONE")
    val U_LLG_DEALER_PHONE: String?,

    @SerializedName("U_LLG_AGENT_DOCUMENT")
    val U_LLG_AGENT_DOCUMENT: String?,

    @SerializedName("U_LLG_ID")
    val U_LLG_ID: String?,

    @SerializedName("U_LLG_ID_PARENT")
    val U_LLG_ID_PARENT: String?,

    @SerializedName("U_LLG_APPROVE")
    val U_LLG_APPROVE: String?
)

/**
 * Representa la estructura de datos que se envía al backend SAP
 * para registrar el SMS en la tabla @ALLG_FISE_SMS.
 */
data class FISE_SMS(

    // Número de la entidad FISE (origen del SMS de respuesta)
    @SerializedName("U_fise_numero")
    val U_fise_numero: String?,

    // Número del agente / supervisor
    @SerializedName("U_usr_numero")
    val U_usr_numero: String?,

    // DNI del usuario / beneficiario
    @SerializedName("U_usr_dni")
    val U_usr_dni: String?,

    // Código del cupón FISE
    @SerializedName("U_fise_codigo")
    val U_fise_codigo: String?,

    // Importe validado
    @SerializedName("U_importe")
    val U_importe: Double?,

    // Número de teléfono del chofer
    @SerializedName("U_usr_chofer")
    val U_usr_chofer: String?,

    // Descripción / mensaje de respuesta de FISE
    @SerializedName("U_descripcion")
    val U_descripcion: String?,

    // 👇 NUEVO: Socio de negocio FISE (SN: C000XXXXXX)
    @SerializedName("U_LLG_FISE_SN")
    val U_LLG_FISE_SN: String?
)
