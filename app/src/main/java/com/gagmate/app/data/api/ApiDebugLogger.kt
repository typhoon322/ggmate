package com.gagmate.app.data.api

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs RAW JSON responses from Gaggiuino v3 REST API endpoints whose
 * response format hasn't been confirmed yet.
 *
 * Log file location: [cacheDir]/gagmate_api_debug/api_debug.log
 *
 * Purpose: when the user connects the machine, open the app → trigger
 * the relevant features (load profiles, refresh dashboard, etc.) → then
 * send me this log file so I can see the actual JSON format.
 *
 * Currently tracking:
 *   - GET /api/system/status
 *   - GET /api/profiles/all
 *   - GET /api/shots/latest
 *   - GET /api/shots/{id}
 */
object ApiDebugLogger {

    private const val LOG_DIR = "gagmate_api_debug"
    private const val LOG_FILE = "api_debug.log"
    private const val MAX_LOG_SIZE = 2L * 1024 * 1024  // 2 MB

    private var logDir: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Call once from Application / MainActivity. */
    fun init(context: Context) {
        logDir = File(context.cacheDir, LOG_DIR).also { it.mkdirs() }
    }

    /**
     * Log a raw response body for a specific endpoint.
     *
     * @param endpoint  The API path, e.g. "/api/system/status"
     * @param statusCode HTTP status, 0 if error
     * @param body      The raw response body as a JSON string
     */
    fun logResponse(endpoint: String, statusCode: Int, body: String) {
        val dir = logDir ?: return
        rotateIfNeeded(dir)

        val ts = dateFmt.format(Date())
        FileWriter(File(dir, LOG_FILE), true).use { w ->
            w.write("[$ts]\n")
            w.write("--- $endpoint ---\n")
            w.write("HTTP $statusCode\n")
            w.write(body)
            w.write("\n\n")
        }
    }

    /** Log an error (connection failure, timeout, etc.) */
    fun logError(endpoint: String, error: String) {
        val dir = logDir ?: return
        rotateIfNeeded(dir)

        val ts = dateFmt.format(Date())
        FileWriter(File(dir, LOG_FILE), true).use { w ->
            w.write("[$ts]\n")
            w.write("--- $endpoint ---\n")
            w.write("ERROR: $error\n\n")
        }
    }

    /** Get the log file, or null if none exists yet. */
    fun getLogFile(): File? {
        val dir = logDir ?: return null
        val f = File(dir, LOG_FILE)
        return f.takeIf { it.exists() }
    }

    private fun rotateIfNeeded(dir: File) {
        val f = File(dir, LOG_FILE)
        if (f.exists() && f.length() > MAX_LOG_SIZE) {
            File(dir, "api_debug_old.log").also { f.renameTo(it) }
        }
    }
}
