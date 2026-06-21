package com.yatori.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yatori.android.data.local.dao.AccountDao
import com.yatori.android.data.local.dao.LogDao
import com.yatori.android.data.local.dao.TaskDao
import com.yatori.android.data.local.entity.AccountEntity
import com.yatori.android.data.local.entity.LogEntity
import com.yatori.android.data.local.entity.TaskEntity

@Database(
    entities = [AccountEntity::class, LogEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun logDao(): LogDao
    abstract fun taskDao(): TaskDao
}
