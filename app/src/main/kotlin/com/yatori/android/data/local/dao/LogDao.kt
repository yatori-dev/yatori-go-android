package com.yatori.android.data.local.dao

import androidx.room.*
import com.yatori.android.data.local.entity.LogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE (:level IS NULL OR level = :level) AND (:keyword IS NULL OR message LIKE '%' || :keyword || '%') AND (:account IS NULL OR account = :account) ORDER BY timestamp DESC LIMIT :limit")
    fun observeFiltered(level: String?, keyword: String?, account: String? = null, limit: Int = 500): Flow<List<LogEntity>>

    @Query("SELECT DISTINCT account FROM logs WHERE account != '' ORDER BY account")
    fun observeAccounts(): Flow<List<String>>

    @Insert
    suspend fun insert(log: LogEntity)

    @Query("DELETE FROM logs")
    suspend fun clear()

    @Query("DELETE FROM logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
