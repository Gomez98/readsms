package com.simplemobiletools.smsmessenger.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.simplemobiletools.smsmessenger.models.Transaction
import com.simplemobiletools.smsmessenger.models.TxStatus

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(transaction: Transaction): Long

    @Query("UPDATE transactions SET estado = :estado, monto = :monto, respuesta = :respuesta WHERE cupon = :cupon AND dni = :dni")
    fun updateTransaction(cupon: String, dni: String, estado: String, monto: Double?, respuesta: String?): Int

    @Query("SELECT * FROM transactions WHERE cupon = :cupon AND dni = :dni AND estado = 'PENDING' AND fecha >= DATETIME('now','-1 hour') LIMIT 1")
    fun getTxByCuponAndDni(cupon: String, dni: String): Transaction?
}