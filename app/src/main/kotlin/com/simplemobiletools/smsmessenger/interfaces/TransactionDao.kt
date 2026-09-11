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

    // Solo hace match con una solicitud PENDING de la última hora.
    @Query("""
        SELECT * FROM transactions 
        WHERE cupon = :cupon 
          AND dni = :dni 
          AND estado = 'PENDING'
          AND datetime(fecha) >= datetime('now', 'localtime', '-1 hour')
        ORDER BY fecha DESC
        LIMIT 1
    """)
    fun getTxByCuponAndDni(cupon: String, dni: String): Transaction?

    // Fallback por cupón, con la misma vigencia máxima.
    @Query("""
        SELECT * FROM transactions 
        WHERE cupon = :cupon 
          AND estado = 'PENDING'
          AND datetime(fecha) >= datetime('now', 'localtime', '-1 hour')
        ORDER BY fecha DESC
        LIMIT 1
    """)
    fun getTxByCuponOnly(cupon: String): Transaction?

    @Query("SELECT * FROM transactions WHERE operation_id = :operationId LIMIT 1")
    fun getTxByOperationId(operationId: String): Transaction?

    @Query("""
        SELECT * FROM transactions
        WHERE estado = 'PENDING'
          AND datetime(fecha) >= datetime('now', 'localtime', '-1 hour')
          AND (entidad = :entidad OR entidad LIKE '%' || :entidad OR :entidad LIKE '%' || entidad)
        ORDER BY fecha DESC
        LIMIT 1
    """)
    fun getLatestPendingByEntidad(entidad: String): Transaction?

    @Query("""
        UPDATE transactions
        SET estado = 'EXPIRED'
        WHERE estado = 'PENDING'
          AND datetime(fecha) < datetime('now', 'localtime', '-1 hour')
    """)
    fun expireOldPending(): Int

    // 👇 Método solo de debug, para ver lo que hay en la tabla
    @Query("""
        SELECT * FROM transactions
        ORDER BY fecha DESC
        LIMIT 20
    """)
    fun debugGetLastTransactions(): List<Transaction>
}
