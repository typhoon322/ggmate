package com.gagmate.app.data.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs all network request/response data to a text file for debugging.
 * Stores logs in [cacheDir]/gagmate_network/gagmate_network.log,
 * rotates at 5MB.
 *
 * Usage:
 *   NetworkLogger.init(applicationContext)
 *   NetworkLogger.log("GET", "/api/state", null, 200, "{...}")
 *   NetworkLogger.share(context)  // share via system intent
 */
object NetworkLogger {

    private const val MAX_LOG_SIZE = 5L * 1024 * 1024  // 5 MB
    private const val LOG_DIR = "gagmate_network"
    private const val LOG_FILE = "gagmate_network.log"
    private const val LOG_FILE_OLD = "gagmate_network_old.log"

    private var logDir: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

    // ── Initialisation ──────────────────────────────────────────────

    /** Call once from Application or MainActivity.onCreate(). */
    fun init(context: Context) {
        logDir = File(context.cacheDir, LOG_DIR).also { it.mkdirs() }
    }

    // ── Public helpers ──────────────────────────────────────────────

    /** The live log file, or null if logging hasn't been initialised. */
    /** The log directory, or null if not initialised. */
    fun getLogDir(): File? = logDir

    fun getLogFile(): File? {
        val dir = logDir ?: return null
        val f = File(dir, LOG_FILE)
        return f.takeIf { it.exists() }
    }

    /** Delete the current log so the next capture starts fresh. */
    fun clearLog() {
        getLogFile()?.delete()
        File(logDir!!, LOG_FILE_OLD).delete()
    }

    /**
     * Open the system share sheet to send the log file.
     * Requires a [FileProvider][androidx.core.content.FileProvider]
     * authority of `com.gagmate.app.fileprovider`.
     */
    fun share(context: Context) {
        val logFile = getLogFile() ?: run {
            // Nothing to share yet
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Network Log"))
    }

    /** Approximate size of the current log (for display). */
    fun logSize(): Long = getLogFile()?.length() ?: 0L

    /** True when a log file exists. */
    fun hasLog(): Boolean = getLogFile() != null

    // ── Logging ─────────────────────────────────────────────────────

    /**
     * Record a single HTTP request/response pair.
     */
    fun log(
        method: String,
        url: String,
        requestBody: String?,
        statusCode: Int,
        responseBody: String?,
        durationMs: Long,
    ) {
        val dir = logDir ?: return
        rotateIfNeeded(dir)

        val timestamp = dateFmt.format(Date())

        FileWriter(File(dir, LOG_FILE), true).use { w ->
            w.write("[$timestamp]\n")
            w.write(">>> $method $url\n")
            w.write("  Duration: ${durationMs}ms\n")
            if (!requestBody.isNullOrEmpty()) {
                w.write("  Request body: $requestBody\n")
            }
            w.write("<<< $statusCode\n")
            if (!responseBody.isNullOrEmpty()) {
                // Truncate very large response bodies (>10k chars) for readability
                val body = if (responseBody.length > 10_000)
                    responseBody.take(10_000) + "\n  … (truncated, ${responseBody.length} chars total)"
                else
                    responseBody
                w.write("  Response body: $body\n")
            }
            w.write("---\n\n")
        }
    }

    /**
     * Record a failed request (connection error, timeout, …).
     */
    fun logError(
        method: String,
        url: String,
        requestBody: String?,
        error: String,
    ) {
        val dir = logDir ?: return
        rotateIfNeeded(dir)

        val timestamp = dateFmt.format(System.currentTimeMillis())

        FileWriter(File(dir, LOG_FILE), true).use { w ->
            w.write("[$timestamp]\n")
            w.write(">>> $method $url\n")
            if (!requestBody.isNullOrEmpty()) {
                w.write("  Request body: $requestBody\n")
            }
            w.write("<<< ERROR: $error\n")
            w.write("---\n\n")
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun rotateIfNeeded(dir: File) {
        val f = File(dir, LOG_FILE)
        if (f.exists() && f.length() > MAX_LOG_SIZE) {
            f.renameTo(File(dir, LOG_FILE_OLD))
        }
    }
}
