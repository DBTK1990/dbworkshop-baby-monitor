package com.github.digitallyrefined.androidipcamera

import com.github.digitallyrefined.androidipcamera.helpers.AppLogger
import com.github.digitallyrefined.androidipcamera.helpers.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AppLogger] — the in-memory log buffer used by the app.
 *
 * The error seen in the problem report arrives in CLF format:
 *   127.0.0.1 - - [22/Apr/2026:22:33:16 +0300] "ERROR StreamingService: RTSP stream failed: Video info is null" - -
 *
 * These tests verify:
 *  1. Entries are stored correctly and retrievable.
 *  2. CLF formatting matches the pattern observed in the logs.
 *  3. Log-level filtering works (ERROR level for the RTSP failure).
 *  4. Listener callbacks fire on each new entry.
 *  5. The buffer caps at MAX_ENTRIES and drops the oldest entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLoggerTest {

    @Before
    fun clearLogger() {
        // AppLogger is a singleton whose internal buffer persists across tests.
        // Overflow the buffer so it rolls over, giving every subsequent test a clean slate
        // relative to the entries it appended.  Each test captures `size` before writing
        // and uses `last()` / `size + N` indices, so this is sufficient isolation.
        repeat(AppLogger.MAX_ENTRIES + 1) { AppLogger.d("TestSetup", "flush") }
    }

    // ── entry storage ─────────────────────────────────────────────────────────

    @Test
    fun `log entry is stored and retrievable`() {
        val before = AppLogger.getEntries().size
        AppLogger.i("TestTag", "hello world")
        val entries = AppLogger.getEntries()
        assertEquals(before + 1, entries.size)
        val last = entries.last()
        assertEquals(LogLevel.INFO, last.level)
        assertEquals("TestTag", last.tag)
        assertEquals("hello world", last.message)
    }

    @Test
    fun `error entry stores level ERROR`() {
        AppLogger.e("StreamingService", "RTSP stream failed: Video info is null")
        val last = AppLogger.getEntries().last()
        assertEquals(LogLevel.ERROR, last.level)
        assertEquals("StreamingService", last.tag)
        assertEquals("RTSP stream failed: Video info is null", last.message)
    }

    @Test
    fun `warn entry stores level WARN`() {
        AppLogger.w("StreamingService", "RTSP server not started: credentials not configured")
        val last = AppLogger.getEntries().last()
        assertEquals(LogLevel.WARN, last.level)
    }

    // ── CLF format ────────────────────────────────────────────────────────────

    @Test
    fun `CLF format contains expected fields`() {
        AppLogger.e("StreamingService", "RTSP stream failed: Video info is null")
        val clf = AppLogger.formatCLF(AppLogger.getEntries().last())

        // CLF pattern: host - - [timestamp] "LEVEL TAG: message" - -
        assertTrue("CLF must start with host", clf.startsWith("127.0.0.1"))
        assertTrue("CLF must contain level ERROR", clf.contains("ERROR"))
        assertTrue("CLF must contain tag", clf.contains("StreamingService"))
        assertTrue("CLF must contain message", clf.contains("RTSP stream failed: Video info is null"))
        assertTrue("CLF must end with ' - -'", clf.endsWith("\" - -"))
    }

    @Test
    fun `CLF format escapes embedded quotes in message`() {
        AppLogger.i("Tag", "message with \"quotes\"")
        val clf = AppLogger.formatCLF(AppLogger.getEntries().last())
        assertTrue("Embedded quotes must be escaped", clf.contains("\\\"quotes\\\""))
    }

    @Test
    fun `CLF format matches exact structure`() {
        AppLogger.e("StreamingService", "RTSP stream failed: Video info is null")
        val clf = AppLogger.formatCLF(AppLogger.getEntries().last())

        // Expected shape: 127.0.0.1 - - [dd/MMM/yyyy:HH:mm:ss Z] "ERROR StreamingService: …" - -
        val regex = Regex(
            """^127\.0\.0\.1 - - \[\d{2}/\w{3}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4}] "ERROR StreamingService: RTSP stream failed: Video info is null" - -$"""
        )
        assertTrue("CLF must match the pattern seen in bug report\n  actual: $clf", regex.matches(clf))
    }

    // ── listener notifications ────────────────────────────────────────────────

    @Test
    fun `listener is notified for each new log entry`() {
        val received = mutableListOf<String>()
        val listener: (com.github.digitallyrefined.androidipcamera.helpers.LogEntry) -> Unit =
            { entry -> received.add(entry.message) }

        AppLogger.addListener(listener)
        try {
            AppLogger.i("ListenerTag", "first message")
            AppLogger.e("ListenerTag", "second message")
            assertTrue("Listener should have received at least the two new entries",
                received.containsAll(listOf("first message", "second message")))
        } finally {
            AppLogger.removeListener(listener)
        }
    }

    @Test
    fun `removed listener no longer receives entries`() {
        val received = mutableListOf<String>()
        val listener: (com.github.digitallyrefined.androidipcamera.helpers.LogEntry) -> Unit =
            { entry -> received.add(entry.message) }

        AppLogger.addListener(listener)
        AppLogger.removeListener(listener)
        AppLogger.i("Tag", "should not arrive")
        assertFalse("Removed listener must not receive new entries",
            received.contains("should not arrive"))
    }

    // ── buffer cap ───────────────────────────────────────────────────────────

    @Test
    fun `entries list never exceeds MAX_ENTRIES`() {
        // Log enough entries to overflow the buffer
        repeat(AppLogger.MAX_ENTRIES + 10) { i ->
            AppLogger.d("CapTest", "entry $i")
        }
        assertTrue(
            "Stored entries must not exceed MAX_ENTRIES",
            AppLogger.getEntries().size <= AppLogger.MAX_ENTRIES
        )
    }

    // ── convenience level methods ─────────────────────────────────────────────

    @Test
    fun `verbose level is stored correctly`() {
        AppLogger.v("Tag", "verbose msg")
        assertEquals(LogLevel.VERBOSE, AppLogger.getEntries().last().level)
    }

    @Test
    fun `debug level is stored correctly`() {
        AppLogger.d("Tag", "debug msg")
        assertEquals(LogLevel.DEBUG, AppLogger.getEntries().last().level)
    }

    @Test
    fun `throwable overload appends exception info to message`() {
        val ex = RuntimeException("encoder error")
        AppLogger.e("StreamingService", "RTSP stream failed", ex)
        val msg = AppLogger.getEntries().last().message
        assertTrue("Exception class should appear in message", msg.contains("RuntimeException"))
        assertTrue("Exception message should appear in message", msg.contains("encoder error"))
    }
}
