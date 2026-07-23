package com.gagmate.app.data.repository

import android.content.Context
import com.gagmate.app.data.local.AppDatabase
import com.gagmate.app.data.session.MachineSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Repository for machine REST calls (profile detail, etc.). */
    lateinit var machineRepo: MachineRepository
        private set

    /** True after [init] completes. */
    var isInitialised: Boolean = false
        private set

    /** App-wide coroutine scope (lives for the whole process). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        localRepo = LocalDataRepository(db)

        machineSession = MachineSessionManager()
        syncManager = SyncManager(localRepo, MachineRepository(), machineSession)
        sensorRepo = SensorRepository(machineSession)
        shotRepo = ShotRepository(machineSession)
        profileRepo = ProfileRepository(localRepo, MachineRepository(), machineSession, syncManager)
        machineRepo = MachineRepository()

        // Begin streaming live shot data into the rolling chart buffer
        // immediately, independent of which screen is currently shown.
        shotRepo.start(appScope)

        isInitialised = true
    }
}
