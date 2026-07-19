package com.gagmate.app.data.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and writes them to a crash log file.
 * Initialize in MainActivity.onCreate().
 *
 * Log file: cacheDir/gagmate_crash/crash.log
 * Sharable via Settings screen.
 */
object CrashLogger {

    private const val LOG_DIR = "gagmate_crash"
    private const val LOG_FILE = "crash.log"
    private const val MAX_LOG_SIZE = 2L * 1024 * 1024  // 2 MB

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var logDir: File? = null
    private var appContext: Context? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Call from MainActivity.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        logDir = File(context.cacheDir, LOG_DIR).also { it.mkdirs() }

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(throwable, thread)
            // Re-throw to original handler (or kill process)
            originalHandler?.uncaughtException(thread, throwable)
            // If no original handler, kill the process
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Write a crash report to the log file. */
    fun logCrash(throwable: Throwable, thread: Thread? = null) {
        val dir = logDir ?: return
        rotateIfNeeded(dir)

        val timestamp = dateFmt.format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        FileWriter(File(dir, LOG_FILE), true).use { w ->
            w.write("========================================\n")
            w.write("Crash at: $timestamp\n")
            w.write("Thread: ${thread?.name ?: "unknown"}\n")
            w.write("Stack trace:\n")
            w.write(sw.toString())
            w.write("\n")
            // Also log app version and device info
            try {
                val ctx = appContext
                if (ctx != null) {
                    val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    w.write("App version: ${pkgInfo.versionName} (${pkgInfo.versionCode})\n")
                    w.write("Android SDK: ${android.os.Build.VERSION.SDK_INT}\n")
                    w.write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                }
            } catch (_: Exception) { }
            w.write("========================================\n\n")
        }
    }

    /** Get the crash log file, or null if none exists. */
    fun getLogFile(): File? {
        val dir = logDir ?: return null
        val f = File(dir, LOG_FILE)
        return f.takeIf { it.exists() }
    }

    /** Check if a crash log exists. */
    fun hasLog(): Boolean = getLogFile()?.exists() == true

    /** Share the crash log via system intent. */
    fun share(context: Context) {
        val logFile = getLogFile() ?: return
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
        context.startActivity(Intent.createChooser(intent, "Share Crash Log"))
    }

    /** Delete the crash log. */
    fun clear() {
        getLogFile()?.delete()
    }

    private fun rotateIfNeeded(dir: File) {
        val f = File(dir, LOG_FILE)
        if (f.exists() && f.length() > MAX_LOG_SIZE) {
            File(dir, "crash_old.log").also { f.renameTo(it) }
        }
    }
}
