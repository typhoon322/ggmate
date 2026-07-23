package com.gagmate.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gagmate.app.data.local.dao.MachineSettingsDao
import com.gagmate.app.data.local.dao.ProfileDao
import com.gagmate.app.data.local.dao.ShotDao
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.ShotEntity

@Database(
    entities = [
        ProfileEntity::class,
        ShotEntity::class,
        MachineSettingsEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun shotDao(): ShotDao
    abstract fun machineSettingsDao(): MachineSettingsDao

    companion object {
        private const val DB_NAME = "gagmate.db"

        /**
         * v3 → v4: normalize inconsistent shot timestamps into epoch milliseconds.
         *
         * Historic records were stored with mixed units (Unix seconds, epoch ms,
         * or ms over-scaled by 1000). Collapse every variant into canonical ms,
         * mirroring [com.gagmate.app.util.normalizeShotTimestamp]:
         *   - <= 1e12   → seconds        → ×1000
         *   - >  1e15   → ms ×1000       → ÷1000
         *   - else      → already ms     → unchanged
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE shot_records
                    SET timestamp = CASE
                        WHEN timestamp <= 1000000000000 THEN timestamp * 1000
                        WHEN timestamp > 1000000000000000 THEN timestamp / 1000
                        ELSE timestamp
                    END
                    WHERE timestamp > 0
                    """.trimIndent()
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
