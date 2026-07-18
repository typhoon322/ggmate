package com.gagmate.app.data.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "",
    val message: String = ""
) {
    val timeFormatted: String get() =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

object DebugLogState {
    private const val MAX_ENTRIES = 500
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var _enabled = false
    val enabled: Boolean get() = _enabled

    fun enable() { _enabled = true }
    fun disable() { _enabled = false }

    fun add(source: String, message: String) {
        if (!_enabled) return
        val entry = LogEntry(source = source, message = message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() { _entries.value = emptyList() }
}
