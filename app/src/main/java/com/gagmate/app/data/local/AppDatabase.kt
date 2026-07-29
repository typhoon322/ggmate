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
    version = 6,
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

        /**
         * v5 → v6: persist the shot-embedded profile so it can act as the
         * authoritative phase/curve source offline.
         *
         * REST `GET /api/profile/{id}` is dead on this firmware (returns the SPA
         * HTML) and WS `d_prof` carries no curve enum, so the only reliable place
         * to read real EASE_* curve strings is the profile embedded inside each
         * shot record. We add `profile_id` and `embedded_phases_json` columns to
         * `shot_records`.
         *
         * IMPORTANT: column nullability MUST mirror [ShotEntity] exactly —
         * `profileId` is `String?`, so `profile_id` must be a plain nullable
         * TEXT. Declaring it NOT NULL here made Room's post-migration schema
         * validation throw `Migration didn't properly handle: shot_records`,
         * which crashed every screen touching the DB (the failed migration
         * transaction rolls back, so the DB stays at v5 and re-fails on each
         * open). `embedded_phases_json` maps to a non-null `String` and keeps
         * NOT NULL DEFAULT '[]'.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shot_records ADD COLUMN profile_id TEXT")
                db.execSQL("ALTER TABLE shot_records ADD COLUMN embedded_phases_json TEXT NOT NULL DEFAULT '[]'")
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
