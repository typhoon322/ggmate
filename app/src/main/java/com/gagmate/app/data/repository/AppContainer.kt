package com.gagmate.app.data.repository

import android.content.Context
import com.gagmate.app.data.local.AppDatabase
import com.gagmate.app.data.session.MachineSessionManager

/**
 * Simple service locator – keeps global singletons for the app's lifetime.
 *
 * Initialised from [com.gagmate.app.MainActivity].
 */
object AppContainer {

    lateinit var db: AppDatabase
        private set

    lateinit var localRepo: LocalDataRepository
        private set

    lateinit var syncManager: SyncManager
        private set

    /** Global WebSocket session to the Gaggiuino machine. */
    lateinit var machineSession: MachineSessionManager
        private set

    /** Repository for live sensor data (subscribes to [machineSession]). */
    lateinit var sensorRepo: SensorRepository
        private set

    /** Repository for live brew shot data. */
    lateinit var shotRepo: ShotRepository
        private set

    /** Repository for profile management. */
    lateinit var profileRepo: ProfileRepository
        private set

    /** True after [init] completes. */
    var isInitialised: Boolean = false
        private set

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        localRepo = LocalDataRepository(db)

        machineSession = MachineSessionManager()
        syncManager = SyncManager(localRepo, MachineRepository(), machineSession)
        sensorRepo = SensorRepository(machineSession)
        shotRepo = ShotRepository(machineSession)
        profileRepo = ProfileRepository(localRepo, MachineRepository(), machineSession, syncManager)

        isInitialised = true
    }
}
