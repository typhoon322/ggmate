package com.gagmate.app.data.local.entity

/**
 * Track the synchronisation state of a local record relative to the machine.
 */
enum class SyncStatus {
    /** Local copy matches the machine. */
    SYNCED,
    /** Created locally, not yet on the machine. */
    LOCAL_ONLY,
    /** Modified locally, pending upload to the machine. */
    MODIFIED,
    /** Both local and machine versions changed since last sync – needs user input. */
    CONFLICT
}
