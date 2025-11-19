package com.simplemobiletools.smsmessenger.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.simplemobiletools.smsmessenger.helpers.*

@Entity(tableName = TABLE_TX)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = TX_ID)
    var id: Long = 0,

    @ColumnInfo(name = TX_DRIVER_PHONE)
    var driverPhone: String,

    @ColumnInfo(name = TX_ENTIDAD)
    var entidad: String,

    @ColumnInfo(name = TX_AGENTE_PHONE)
    var agentePhone: String,

    @ColumnInfo(name = TX_CUPON)
    var cupon: String,

    @ColumnInfo(name = TX_DNI)
    var dni: String,

    @ColumnInfo(name = TX_FECHA)
    var fecha: String,

    @ColumnInfo(name = TX_MONTO)
    var monto: Double?,

    @ColumnInfo(name = TX_ESTADO)
    var estado: String?,

    @ColumnInfo(name = TX_RESPUESTA)
    var respuesta: String?,

    @ColumnInfo(name = TX_SN)
    var sn: String? = null
)
