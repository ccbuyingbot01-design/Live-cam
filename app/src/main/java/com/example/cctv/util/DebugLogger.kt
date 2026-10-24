package com.example.cctv.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val stackTrace: String? = null
)

enum class LogLevel {
    INFO, WARN, ERROR, CRASH
}

object DebugLogger {

    private const val MAX_LOGS = 200
    private var idCounter = 0L

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val _latestCrash = MutableStateFlow<DebugLogEntry?>(null)
    val latestCrash: StateFlow<DebugLogEntry?> = _latestCrash.asStateFlow()

    private val _hasErrors = MutableStateFlow(false)
    val hasErrors: StateFlow<Boolean> = _hasErrors.asStateFlow()

    private fun getFormattedTime(): String {
        return try {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }
    }

    private var isHandlerInstalled = false

    fun init(context: Context) {
        if (!isHandlerInstalled) {
            isHandlerInstalled = true
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val timeStr = getFormattedTime()
                val stackWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(stackWriter))
                val fullTrace = stackWriter.toString()

                val entry = DebugLogEntry(
                    id = ++idCounter,
                    timestamp = timeStr,
                    level = LogLevel.CRASH,
                    tag = "UNCAUGHT_CRASH",
                    message = "Crash on Thread [${thread.name}]: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}",
                    stackTrace = fullTrace
                )

                addEntry(entry)
                _latestCrash.value = entry
                _hasErrors.value = true

                Log.e("DebugLogger", "UNCAUGHT CRASH INTERCEPTED: ${entry.message}\n$fullTrace")

                try {
                    val prefs = context.getSharedPreferences("debug_crash_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_crash", "${entry.timestamp}\n${entry.message}\n$fullTrace").apply()
                } catch (_: Throwable) {}

                defaultHandler?.uncaughtException(thread, throwable)
            }

            try {
                val prefs = context.getSharedPreferences("debug_crash_prefs", Context.MODE_PRIVATE)
                val lastCrashStr = prefs.getString("last_crash", null)
                if (!lastCrashStr.isNullOrBlank()) {
                    addEntry(
                        DebugLogEntry(
                            id = ++idCounter,
                            timestamp = "PREV_SESSION",
                            level = LogLevel.CRASH,
                            tag = "RESTORED_CRASH",
                            message = "Crash log restored from previous run:",
                            stackTrace = lastCrashStr
                        )
                    )
                    _hasErrors.value = true
                }
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    private fun addEntry(entry: DebugLogEntry) {
        val current = _logs.value.toMutableList()
        if (current.size >= MAX_LOGS) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
        if (entry.level == LogLevel.ERROR || entry.level == LogLevel.CRASH) {
            _hasErrors.value = true
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addEntry(
            DebugLogEntry(
                id = ++idCounter,
                timestamp = getFormattedTime(),
                level = LogLevel.INFO,
                tag = tag,
                message = message
            )
        )
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        val trace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        addEntry(
            DebugLogEntry(
                id = ++idCounter,
                timestamp = getFormattedTime(),
                level = LogLevel.WARN,
                tag = tag,
                message = message,
                stackTrace = trace
            )
        )
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val trace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        addEntry(
            DebugLogEntry(
                id = ++idCounter,
                timestamp = getFormattedTime(),
                level = LogLevel.ERROR,
                tag = tag,
                message = message,
                stackTrace = trace
            )
        )
    }

    fun clear() {
        _logs.value = emptyList()
        _latestCrash.value = null
        _hasErrors.value = false
    }

    fun getFormattedLogs(): String {
        val sb = StringBuilder()
        sb.append("=== LOVE CCTV DEBUG LOGS & CRASH CONSOLE ===\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")

        for (log in _logs.value) {
            sb.append("[${log.timestamp}] [${log.level}] [${log.tag}]: ${log.message}\n")
            if (!log.stackTrace.isNullOrBlank()) {
                sb.append("Stack Trace:\n${log.stackTrace}\n")
            }
            sb.append("--------------------------------------------------\n")
        }
        return sb.toString()
    }
}
