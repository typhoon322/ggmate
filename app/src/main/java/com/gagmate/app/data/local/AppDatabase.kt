package com.gagmate.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun shotDao(): ShotDao
    abstract fun machineSettingsDao(): MachineSettingsDao

    companion object {
        private const val DB_NAME = "gagmate.db"

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
