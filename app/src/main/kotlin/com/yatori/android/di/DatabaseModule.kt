package com.yatori.android.di

import android.content.Context
import androidx.room.Room
import com.yatori.android.data.local.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "yatori.db").build()

    @Provides fun provideAccountDao(db: AppDatabase) = db.accountDao()
    @Provides fun provideLogDao(db: AppDatabase) = db.logDao()
    @Provides fun provideTaskDao(db: AppDatabase) = db.taskDao()
}
