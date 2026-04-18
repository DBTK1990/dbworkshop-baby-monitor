package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.Color
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val label: String, val color: Int) {
    VERBOSE("VERBOSE", Color.parseColor("#B0B0B0")),
    DEBUG(  "DEBUG",   Color.parseColor("#00BFFF")),
    INFO(   "INFO",    Color.parseColor("#90EE90")),
    WARN(   "WARN",    Color.parseColor("#FFA500")),
    ERROR(  "ERROR",   Color.parseColor("#FF4444"))
}

data class LogEntry(
    val timestamp: Date,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val host: String = "127.0.0.1"
)

/**
 * Application-wide logger that stores entries in Common Log Format (CLF) and
 * notifies registered listeners on every new entry.
 *
 * CLF pattern: host ident authuser [timestamp] "LEVEL TAG: message" - -
 */
object AppLogger {

    const val MAX_ENTRIES = 1_000

    // LinkedList stored directly so we can use removeFirst() for O(1) removal at head.
    private val rawEntries: LinkedList<LogEntry> = LinkedList()
    private val entries: MutableList<LogEntry> = Collections.synchronizedList(rawEntries)

    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    // One formatter per thread — avoids allocating a new SimpleDateFormat on every formatCLF() call.
    private val clfFormatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US)
    }

    // ---------- public logging API ----------

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG,   tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO,    tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN,    tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR,   tag, message)

    /** Overloads that accept a [Throwable] — forwards the full exception to logcat. */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
        log(LogLevel.WARN, tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        log(LogLevel.ERROR, tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    fun log(level: LogLevel, tag: String, message: String, host: String = "127.0.0.1") {
        val entry = LogEntry(Date(), level, tag, message, host)
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) rawEntries.removeFirst()
        }
        // Forward to Android logcat as well
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message)
            LogLevel.DEBUG   -> Log.d(tag, message)
            LogLevel.INFO    -> Log.i(tag, message)
            LogLevel.WARN    -> Log.w(tag, message)
            LogLevel.ERROR   -> Log.e(tag, message)
        }
        listeners.forEach { it(entry) }
    }

    // ---------- observer API ----------

    fun addListener(listener: (LogEntry) -> Unit)    { listeners.add(listener) }
    fun removeListener(listener: (LogEntry) -> Unit) { listeners.remove(listener) }

    /** Snapshot of all stored entries (thread-safe copy). */
    fun getEntries(): List<LogEntry> = synchronized(entries) { ArrayList(entries) }

    // ---------- CLF formatting ----------

    /**
     * Returns the entry formatted in Apache Common Log Format:
     *   host ident authuser [dd/MMM/yyyy:HH:mm:ss Z] "LEVEL TAG: message" - -
     */
    fun formatCLF(entry: LogEntry): String {
        val timestamp = clfFormatter.get()!!.format(entry.timestamp)
        val escaped   = entry.message.replace("\"", "\\\"")
        return "${entry.host} - - [$timestamp] \"${entry.level.label} ${entry.tag}: $escaped\" - -"
    }
}
