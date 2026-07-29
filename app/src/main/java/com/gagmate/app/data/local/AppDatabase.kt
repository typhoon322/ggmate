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
    version = 5,
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

        /**
         * v4 → v5: wipe stale, corrupted phases_json written by older WS decoders.
         *
         * Those records stored raw protobuf bytes in the "name" field and all-zero
         * target/time/variation (FLAT), causing profile detail to show garbage.
         * Reset SYNCED profiles to an empty phase list so the next full sync can
         * re-seed them from REST GET /api/profile/{id} (the reliable source of
         * real EASE_* curve types). User-edited profiles (MODIFIED/CONFLICT/
         * LOCAL_ONLY) are left untouched.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE profiles SET phases_json = '[]' WHERE sync_status = 'SYNCED'")
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
