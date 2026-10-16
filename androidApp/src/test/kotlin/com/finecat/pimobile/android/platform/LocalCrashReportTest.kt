package com.finecat.pimobile.android.platform

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCrashReportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(file: File = File(temporary.root, "last-crash.txt")) =
        LocalCrashReport(file, "1.0.0", 44, 35, nowMillis = { 123456L })

    @Test fun reportContainsCodeLocationAndVersionButNeverMessagesPathsOrThreadName() {
        val file = File(temporary.root, "last-crash.txt")
        val error = IllegalStateException("sk-private-message https://private.invalid:1234 /home/private/chat")
        error.stackTrace = arrayOf(StackTraceElement("q7.d2", "compact", "/home/private/config.kt", 2160))
        error.initCause(IllegalArgumentException("private cause draft"))
        error.addSuppressed(Exception("private suppressed API key"))
        val store = report(file)
        store.record(error)
        val saved = report(file).read()!!
        assertTrue(saved.contains("version=1.0.0 code=44 androidSdk=35"))
        assertTrue(saved.contains("timeEpochMillis=123456"))
        assertTrue(saved.contains("exception=java.lang.IllegalStateException"))
        assertTrue(saved.contains("at q7.d2.compact:2160"))
        assertTrue(saved.contains("cause=java.lang.IllegalArgumentException"))
        listOf("sk-private", "private.invalid", "/home/private", "private cause", "suppressed", "config.kt")
            .forEach { assertFalse(it, saved.contains(it)) }
    }

    @Test fun reportRejectsInjectedNonCodeSymbols() {
        val error = Exception("not retained")
        error.stackTrace = arrayOf(StackTraceElement("https://secret.invalid", "token=secret", "secret", 1))
        val store = report()
        store.record(error)
        val saved = store.read()!!
        assertTrue(saved.contains("[redacted-symbol]"))
        assertFalse(saved.contains("secret"))
    }

    @Test fun largeAndCyclicCausesAreBoundedAndLastCrashReplacesPriorRecord() {
        val first = Exception("first")
        val second = Exception("second")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = Array(1000) { StackTraceElement("a".repeat(180), "b".repeat(180), "x", it) }
        val file = File(temporary.root, "last-crash.txt")
        val store = report(file)
        store.record(first)
        assertTrue(file.length() <= 16 * 1024)
        assertTrue(store.read()!!.endsWith("\n[truncated]\n"))
        store.record(UnsupportedOperationException("replacement"))
        assertTrue(store.read()!!.contains("exception=java.lang.UnsupportedOperationException"))
        assertFalse(store.read()!!.contains("exception=java.lang.Exception"))
        assertFalse(File(temporary.root, "last-crash.txt.tmp").exists())
    }

    @Test fun missingOversizedAndUnrecognizedFilesAreNotShownAndClearPersists() {
        val file = File(temporary.root, "last-crash.txt")
        val store = report(file)
        assertNull(store.read())
        file.writeText("unrecognized private file")
        assertNull(store.read())
        Files.write(file.toPath(), ByteArray(16 * 1024 + 1))
        assertNull(store.read())
        store.record(Exception())
        assertTrue(store.clear())
        assertNull(report(file).read())
    }

    @Test fun clearAlsoRemovesAnInterruptedTemporaryWriteWithoutMainReport() {
        val file = File(temporary.root, "last-crash.txt")
        val pending = File(temporary.root, "last-crash.txt.tmp")
        pending.writeText("interrupted diagnostic write")
        assertTrue(report(file).clear())
        assertFalse(pending.exists())
    }

    @Test fun handlerRecordsThenDelegatesOriginalThrowableAndTerminates() {
        val store = report()
        val error = Exception("not retained")
        val thread = Thread.currentThread()
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { actualThread, actualError ->
            assertTrue(actualThread === thread)
            assertTrue(actualError === error)
            assertTrue(store.read()!!.contains("java.lang.Exception"))
            calls += "previous"
        }
        val handler = CrashRecordingHandler(store, previous) { calls += "terminate" }
        handler.uncaughtException(thread, error)
        assertEquals(listOf("previous", "terminate"), calls)
    }

    @Test fun storageFailureCannotPreventOriginalHandlerOrTermination() {
        val parentFile = temporary.newFile("not-a-directory")
        val store = report(File(parentFile, "last-crash.txt"))
        val calls = mutableListOf<String>()
        CrashRecordingHandler(store, Thread.UncaughtExceptionHandler { _, _ -> calls += "previous" }) {
            calls += "terminate"
        }.uncaughtException(Thread.currentThread(), Exception())
        assertEquals(listOf("previous", "terminate"), calls)
    }

    @Test fun missingOrThrowingPreviousHandlerStillTerminates() {
        var terminations = 0
        CrashRecordingHandler(report(), null) { terminations += 1 }
            .uncaughtException(Thread.currentThread(), Exception())
        val failure = IllegalStateException("previous failed")
        try {
            CrashRecordingHandler(report(), Thread.UncaughtExceptionHandler { _, _ -> throw failure }) {
                terminations += 1
            }.uncaughtException(Thread.currentThread(), Exception())
            throw AssertionError("Expected original handler failure")
        } catch (actual: IllegalStateException) {
            assertTrue(actual === failure)
        }
        assertEquals(2, terminations)
    }
}
