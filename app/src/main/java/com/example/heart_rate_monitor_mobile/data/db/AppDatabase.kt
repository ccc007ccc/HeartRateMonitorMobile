package com.example.heart_rate_monitor_mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordingSession::class, SessionDevice::class, HeartRateRecord::class],
    version = 3,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun heartRateDao(): HeartRateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2（单设备会话）→ v3（会话-设备-样本三层）正式迁移：
         * 旧会话 1:1 转为 RecordingSession + 单台主设备；样本原样归属该设备。
         * 显式保留 id 映射（session_devices.id = 旧 sessionId），样本外键直接平移。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE recording_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "startTime INTEGER NOT NULL, endTime INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE session_devices (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, deviceId TEXT NOT NULL, " +
                        "deviceName TEXT NOT NULL, isPrimary INTEGER NOT NULL, " +
                        "FOREIGN KEY(sessionId) REFERENCES recording_sessions(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX index_session_devices_sessionId ON session_devices(sessionId)")
                db.execSQL(
                    "CREATE TABLE heart_rate_records_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionDeviceId INTEGER NOT NULL, timestamp INTEGER NOT NULL, " +
                        "heartRate INTEGER NOT NULL, rr TEXT, " +
                        "FOREIGN KEY(sessionDeviceId) REFERENCES session_devices(id) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO recording_sessions (id, startTime, endTime) " +
                        "SELECT id, startTime, endTime FROM heart_rate_sessions"
                )
                db.execSQL(
                    "INSERT INTO session_devices (id, sessionId, deviceId, deviceName, isPrimary) " +
                        "SELECT id, id, '', deviceName, 1 FROM heart_rate_sessions"
                )
                db.execSQL(
                    "INSERT INTO heart_rate_records_new (id, sessionDeviceId, timestamp, heartRate, rr) " +
                        "SELECT id, sessionId, timestamp, heartRate, NULL FROM heart_rate_records"
                )
                db.execSQL("DROP TABLE heart_rate_records")
                db.execSQL("ALTER TABLE heart_rate_records_new RENAME TO heart_rate_records")
                db.execSQL("CREATE INDEX index_heart_rate_records_sessionDeviceId ON heart_rate_records(sessionDeviceId)")
                db.execSQL("DROP TABLE heart_rate_sessions")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "heart_rate_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
