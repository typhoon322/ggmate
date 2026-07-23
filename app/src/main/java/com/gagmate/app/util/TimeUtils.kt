package com.gagmate.app.util

/**
 * Normalize a Gaggiuino shot timestamp to epoch MILLISECONDS.
 *
 * The raw value has historically arrived in inconsistent units
 * (Unix seconds, epoch milliseconds, or milliseconds over-scaled by 1000).
 * This collapses every known variant into a single canonical millisecond value:
 *
 *  - ts <= 1e12   → treated as Unix seconds         → ×1000
 *  - ts >  1e15   → treated as ms over-scaled ×1000 → ÷1000
 *  - otherwise    → already epoch milliseconds       → unchanged
 *
 * The SQL migration in [com.gagmate.app.data.local.AppDatabase] applies the same
 * thresholds so on-disk data and new writes stay consistent.
 */
fun normalizeShotTimestamp(ts: Long): Long {
    return when {
        ts <= 0L -> 0L
        ts <= 1_000_000_000_000L -> ts * 1000L
        ts > 1_000_000_000_000_000L -> ts / 1000L
        else -> ts
    }
}
