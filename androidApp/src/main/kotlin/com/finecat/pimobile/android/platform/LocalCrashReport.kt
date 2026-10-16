package com.finecat.pimobile.android.platform

import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Only code symbols are recorded: exception messages, thread names and file paths are omitted. */
internal class LocalCrashReport(
    private val file: File,
    private val versionName: String,
    private val versionCode: Int,
    private val androidSdk: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun record(error: Throwable) {
        val fullText = buildString {
            appendLine("Pi Mobile local crash / format 1")
            appendLine("version=${symbol(versionName)} code=$versionCode androidSdk=$androidSdk")
            appendLine("timeEpochMillis=${nowMillis()}")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSES && seen.add(current)) {
                appendLine("${if (depth == 0) "exception" else "cause"}=${symbol(current.javaClass.name)}")
                current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                    appendLine("  at ${symbol(frame.className)}.${symbol(frame.methodName)}:${frame.lineNumber}")
                }
                if (current.stackTrace.size > MAX_FRAMES) appendLine("  [more frames omitted]")
                current = current.cause
                depth += 1
            }
            if (current != null) appendLine("[more causes omitted]")
        }
        val text = if (fullText.length > MAX_BYTES) {
            fullText.take(MAX_BYTES - TRUNCATED.length) + TRUNCATED
        } else fullText
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(pending.renameTo(file)) { "Cannot replace crash report" }
        } finally {
            pending.delete()
        }
    }

    fun read(): String? = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        file.readText(Charsets.UTF_8).takeIf { it.startsWith("Pi Mobile local crash / format 1\n") }
    }.getOrNull()

    fun clear(): Boolean {
        val pending = File(file.parentFile, file.name + ".tmp")
        val deleted = !file.exists() || file.delete()
        val pendingDeleted = !pending.exists() || pending.delete()
        return deleted && pendingDeleted
    }

    private fun symbol(value: String): String =
        if (value.length in 1..180 && value.all { it in 'a'..'z' || it in 'A'..'Z' ||
                it in '0'..'9' || it in "_.$<>-" }) value else "[redacted-symbol]"

    private companion object {
        const val MAX_BYTES = 16 * 1024
        const val MAX_FRAMES = 48
        const val MAX_CAUSES = 4
        const val TRUNCATED = "\n[truncated]\n"
    }
}

/** Recording is best effort; the platform's fatal handler and process termination always follow. */
internal class CrashRecordingHandler(
    private val report: LocalCrashReport,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val recordingStarted = AtomicBoolean()

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (recordingStarted.compareAndSet(false, true)) {
                runCatching { report.record(error) }
            }
        } finally {
            try {
                previous?.uncaughtException(thread, error)
            } finally {
                terminate()
            }
        }
    }
}
