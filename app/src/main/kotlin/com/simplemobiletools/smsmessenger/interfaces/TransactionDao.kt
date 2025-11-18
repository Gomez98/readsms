package com.simplemobiletools.smsmessenger.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.simplemobiletools.smsmessenger.models.Transaction

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(transaction: Transaction): Long

    @Query("""
        UPDATE transactions 
        SET estado = :estado, 
            monto = :monto, 
            respuesta = :respuesta 
        WHERE cupon = :cupon AND dni = :dni
    """)
    fun updateTransaction(
        cupon: String,
        dni: String,
        estado: String,
        monto: Double?,
        respuesta: String?
    ): Int

    // ⬇️ Sin filtro de fecha, solo buscamos el último PENDING
    @Query("""
        SELECT * FROM transactions 
        WHERE cupon = :cupon 
          AND dni = :dni 
          AND estado = 'PENDING'
        ORDER BY fecha DESC
        LIMIT 1
    """)
    fun getTxByCuponAndDni(cupon: String, dni: String): Transaction?

    // ⬇️ Igual para la búsqueda solo por cupón
    @Query("""
        SELECT * FROM transactions 
        WHERE cupon = :cupon 
          AND estado = 'PENDING'
        ORDER BY fecha DESC
        LIMIT 1
    """)
    fun getTxByCuponOnly(cupon: String): Transaction?

    // 👇 Método solo de debug, para ver lo que hay en la tabla
    @Query("""
        SELECT * FROM transactions
        ORDER BY fecha DESC
        LIMIT 20
    """)
    fun debugGetLastTransactions(): List<Transaction>
}
