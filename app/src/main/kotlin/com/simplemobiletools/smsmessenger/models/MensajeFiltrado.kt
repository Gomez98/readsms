package com.simplemobiletools.smsmessenger.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mensajes_filtrados")
data class MensajeFiltrado(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val numero: String,
    val mensaje: String,
    val fecha: Long
)
