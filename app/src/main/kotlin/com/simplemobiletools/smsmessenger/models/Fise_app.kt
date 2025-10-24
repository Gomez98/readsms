package com.simplemobiletools.smsmessenger.models

import com.google.gson.annotations.SerializedName
data class AgenteResponse(
    val value: List<Agente>
)
/**
 * Representa la estructura de datos para un Agente, utilizada para la serialización
 * a JSON al enviar datos al backend SAP.
 */
data class Agente(

    @SerializedName("Code")
    val Code: String?,

    @SerializedName("U_LLG_AGENT_PHONE")
    val U_LLG_AGENT_PHONE: String?,

    @SerializedName("U_LLG_AGENT_IMEI")
    val U_LLG_AGENT_IMEI:String?,

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
 * Representa la estructura de datos para un Agente, utilizada para la serialización
 * a JSON al enviar datos al backend SAP.
 */
data class FISE_SMS(
    @SerializedName("U_fise_numero")
    val U_fise_numero: String?,

    @SerializedName("U_usr_numero")
    val U_usr_numero: String?,

    @SerializedName("U_usr_dni")
    val U_usr_dni: String?,

    @SerializedName("U_fise_codigo")
    val U_fise_codigo: String?,

    @SerializedName("U_importe")
    val U_importe: Double?,

    @SerializedName("U_usr_chofer")
    val U_usr_chofer: String?,

    @SerializedName("U_descripcion")
    val U_descripcion: String?
)
