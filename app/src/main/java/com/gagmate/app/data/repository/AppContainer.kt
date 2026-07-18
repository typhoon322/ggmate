package com.gagmate.app.data.repository

import android.content.Context
import com.gagmate.app.data.local.AppDatabase

/**
 * Simple service locator – keeps a single instance of the database,
 * local-data repository and sync manager alive for the app's lifetime.
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

    /** True after [init] completes. */
    var isInitialised: Boolean = false
        private set

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        localRepo = LocalDataRepository(db)
        syncManager = SyncManager(localRepo, MachineRepository())
        isInitialised = true
    }
}
