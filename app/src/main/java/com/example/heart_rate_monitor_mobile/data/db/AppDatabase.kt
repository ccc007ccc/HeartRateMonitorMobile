package com.example.heart_rate_monitor_mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 1. 将版本号从 1 增加到 2
@Database(entities = [HeartRateSession::class, HeartRateRecord::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun heartRateDao(): HeartRateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "heart_rate_database"
                )
                    // 2. 添加这行代码，以便在版本升级时自动销毁并重建数据库
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
