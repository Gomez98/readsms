package com.simplemobiletools.smsmessenger.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.simplemobiletools.smsmessenger.models.MensajeFiltrado

@Dao
interface MensajeFiltradoDao {
    @Insert
    fun insertar(mensaje: MensajeFiltrado)

    @Query("SELECT * FROM mensajes_filtrados ORDER BY fecha DESC")
    fun obtenerTodos(): List<MensajeFiltrado>
}
