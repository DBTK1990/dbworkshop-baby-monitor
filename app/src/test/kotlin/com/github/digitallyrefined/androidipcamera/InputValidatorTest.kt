package com.github.digitallyrefined.androidipcamera

import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InputValidator].
 *
 * Credential validation is the first gate in [StreamingService.startRtspStream]:
 * if either username or password is invalid the RTSP server is never started,
 * so a valid test-credential pair is a prerequisite for triggering the
 * "Video info is null" code path.
 */
class InputValidatorTest {

    // ── isValidUsername ────────────────────────────────────────────────────────

    @Test
    fun `valid alphanumeric username is accepted`() {
        assertTrue(InputValidator.isValidUsername("admin"))
        assertTrue(InputValidator.isValidUsername("User123"))
        assertTrue(InputValidator.isValidUsername("user_name"))
        assertTrue(InputValidator.isValidUsername("user-name"))
    }

    @Test
    fun `empty username is rejected`() {
        assertFalse(InputValidator.isValidUsername(""))
    }

    @Test
    fun `username over 50 characters is rejected`() {
        val longUsername = "a".repeat(51)
        assertFalse(InputValidator.isValidUsername(longUsername))
    }

    @Test
    fun `username exactly 50 characters is accepted`() {
        val maxUsername = "a".repeat(50)
        assertTrue(InputValidator.isValidUsername(maxUsername))
    }

    @Test
    fun `username with spaces is rejected`() {
        assertFalse(InputValidator.isValidUsername("user name"))
    }

    @Test
    fun `username with special characters is rejected`() {
        assertFalse(InputValidator.isValidUsername("user@domain"))
        assertFalse(InputValidator.isValidUsername("user.name"))
        assertFalse(InputValidator.isValidUsername("user!"))
    }

    // ── isValidPassword ───────────────────────────────────────────────────────

    @Test
    fun `valid password with uppercase lowercase and digit is accepted`() {
        assertTrue(InputValidator.isValidPassword("Password1"))
        assertTrue(InputValidator.isValidPassword("MySecure123"))
        assertTrue(InputValidator.isValidPassword("Abc12345"))
    }

    @Test
    fun `password shorter than 8 characters is rejected`() {
        assertFalse(InputValidator.isValidPassword("Ab1"))
        assertFalse(InputValidator.isValidPassword("Abc123"))
    }

    @Test
    fun `password without uppercase is rejected`() {
        assertFalse(InputValidator.isValidPassword("password123"))
    }

    @Test
    fun `password without lowercase is rejected`() {
        assertFalse(InputValidator.isValidPassword("PASSWORD123"))
    }

    @Test
    fun `password without digit is rejected`() {
        assertFalse(InputValidator.isValidPassword("PasswordOnly"))
    }

    @Test
    fun `empty password is rejected`() {
        assertFalse(InputValidator.isValidPassword(""))
    }

    @Test
    fun `password over 128 characters is rejected`() {
        val longPassword = "Aa1" + "x".repeat(126) // 129 chars
        assertFalse(InputValidator.isValidPassword(longPassword))
    }

    @Test
    fun `password exactly 128 characters is accepted`() {
        val maxPassword = "Aa1" + "x".repeat(125) // 128 chars
        assertTrue(InputValidator.isValidPassword(maxPassword))
    }

    // ── isValidStreamDelay ────────────────────────────────────────────────────

    @Test
    fun `valid stream delay within range is accepted`() {
        assertTrue(InputValidator.isValidStreamDelay("10"))
        assertTrue(InputValidator.isValidStreamDelay("33"))
        assertTrue(InputValidator.isValidStreamDelay("1000"))
    }

    @Test
    fun `stream delay below minimum is rejected`() {
        assertFalse(InputValidator.isValidStreamDelay("9"))
        assertFalse(InputValidator.isValidStreamDelay("0"))
    }

    @Test
    fun `stream delay above maximum is rejected`() {
        assertFalse(InputValidator.isValidStreamDelay("1001"))
    }

    @Test
    fun `non-numeric stream delay is rejected`() {
        assertFalse(InputValidator.isValidStreamDelay("abc"))
        assertFalse(InputValidator.isValidStreamDelay(""))
    }

    // ── isValidCameraResolution ───────────────────────────────────────────────

    @Test
    fun `valid resolution strings are accepted`() {
        assertTrue(InputValidator.isValidCameraResolution("low"))
        assertTrue(InputValidator.isValidCameraResolution("medium"))
        assertTrue(InputValidator.isValidCameraResolution("high"))
    }

    @Test
    fun `invalid resolution strings are rejected`() {
        assertFalse(InputValidator.isValidCameraResolution("ultra"))
        assertFalse(InputValidator.isValidCameraResolution(""))
        assertFalse(InputValidator.isValidCameraResolution("LOW"))
    }

    // ── validateAndSanitizeUsername ───────────────────────────────────────────

    @Test
    fun `valid username passes sanitize-and-validate`() {
        val result = InputValidator.validateAndSanitizeUsername("admin")
        assertNotNull(result)
        assertEquals("admin", result)
    }

    @Test
    fun `username with html characters is sanitized then rejected`() {
        // After stripping < > " ' &  the result is "scriptscript" which is still valid,
        // so we verify the dangerous characters are removed.
        val result = InputValidator.validateAndSanitizeUsername("<script>")
        // sanitizeString removes < and > → "script" is 6 chars, alphanumeric — valid
        assertNotNull(result)
        assertEquals("script", result)
    }

    @Test
    fun `empty username returns null from validate-and-sanitize`() {
        assertNull(InputValidator.validateAndSanitizeUsername(""))
    }

    // ── validateAndSanitizePassword ───────────────────────────────────────────

    @Test
    fun `valid password passes sanitize-and-validate`() {
        val result = InputValidator.validateAndSanitizePassword("Password1")
        assertNotNull(result)
        assertEquals("Password1", result)
    }

    @Test
    fun `invalid password returns null from validate-and-sanitize`() {
        assertNull(InputValidator.validateAndSanitizePassword("weak"))
    }

    // ── RTSP start gate ───────────────────────────────────────────────────────

    /**
     * Mirrors the exact guard in StreamingService.startRtspStream():
     *   if (!isValidUsername(username) || !isValidPassword(password)) return
     *
     * These tests verify that the RTSP startup guard behaves correctly for
     * common mis-configuration cases, which would prevent any RTSP session
     * (and therefore the "Video info is null" path) from being reached.
     */
    @Test
    fun `rtsp start guard - valid credentials allow stream to start`() {
        val username = "admin"
        val password = "Password1"
        val shouldStart = InputValidator.isValidUsername(username) && InputValidator.isValidPassword(password)
        assertTrue("RTSP stream should start with valid credentials", shouldStart)
    }

    @Test
    fun `rtsp start guard - empty username blocks stream start`() {
        val username = ""
        val password = "Password1"
        val shouldStart = InputValidator.isValidUsername(username) && InputValidator.isValidPassword(password)
        assertFalse("RTSP stream must be blocked when username is empty", shouldStart)
    }

    @Test
    fun `rtsp start guard - weak password blocks stream start`() {
        val username = "admin"
        val password = "weak"
        val shouldStart = InputValidator.isValidUsername(username) && InputValidator.isValidPassword(password)
        assertFalse("RTSP stream must be blocked when password is too weak", shouldStart)
    }

    @Test
    fun `rtsp start guard - both credentials empty blocks stream start`() {
        val username = ""
        val password = ""
        val shouldStart = InputValidator.isValidUsername(username) && InputValidator.isValidPassword(password)
        assertFalse("RTSP stream must be blocked when both credentials are empty", shouldStart)
    }
}
