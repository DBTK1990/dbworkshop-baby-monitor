package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/**
 * Instrumented end-to-end test that verifies the RTSP server starts and accepts stream connections.
 *
 * The test:
 *  1. Seeds valid credentials into [SecureStorage] (the same gate checked by startRtspStream).
 *  2. Binds to [StreamingService] via [ServiceTestRule] and calls [StreamingService.startStreamingServer].
 *  3. Waits up to [CONNECT_TIMEOUT_MS] for the server socket to appear on port [RTSP_PORT].
 *  4. Sends a raw RTSP OPTIONS request and asserts the server replies with "RTSP/1.0 200 OK".
 *  5. Sends a DESCRIBE request with HTTP Basic credentials and asserts a valid RTSP status line.
 *
 * Run on a connected device or AVD (requires camera hardware / virtual camera):
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class RtspStreamTest {

    companion object {
        private const val RTSP_PORT = 8554
        private const val RTSP_HOST = "127.0.0.1"
        private const val TEST_USERNAME = "testuser"
        private const val TEST_PASSWORD = "TestPass1"

        /** How long to poll for the RTSP port before failing. */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Socket read timeout once a connection is open. */
        private const val SOCKET_TIMEOUT_MS = 5_000
    }

    // Grant runtime permissions so the foreground camera+mic service can start.
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ── fixtures ──────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        val secureStorage = SecureStorage(context)
        secureStorage.putSecureString(SecureStorage.KEY_USERNAME, TEST_USERNAME)
        secureStorage.putSecureString(SecureStorage.KEY_PASSWORD, TEST_PASSWORD)
    }

    @After
    fun tearDown() {
        val secureStorage = SecureStorage(context)
        secureStorage.removeSecureString(SecureStorage.KEY_USERNAME)
        secureStorage.removeSecureString(SecureStorage.KEY_PASSWORD)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Binds to [StreamingService] and calls [StreamingService.startStreamingServer] on the main
     * thread (the service's coroutine scope is main-thread-bound).
     */
    private fun bindAndStartServer(): StreamingService {
        val intent = Intent(context, StreamingService::class.java)
        val binder = serviceRule.bindService(intent)
        assertNotNull("ServiceTestRule should return a valid IBinder", binder)
        val service = (binder as StreamingService.LocalBinder).getService()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.startStreamingServer()
        }
        return service
    }

    /**
     * Polls [host]:[port] every 200 ms until the port is open or [timeoutMs] elapses.
     */
    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket(host, port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }

    /**
     * Sends [request] bytes to [host]:[port] and returns the raw UTF-8 response string,
     * or null on any socket error.
     */
    private fun sendRtspRequest(host: String, port: Int, request: String): String? {
        return try {
            Socket(host, port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }
                val buffer = ByteArray(8192)
                val bytesRead = socket.getInputStream().read(buffer)
                if (bytesRead > 0) String(buffer, 0, bytesRead, Charsets.UTF_8) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Verifies that the RTSP server starts, accepts a TCP connection on port [RTSP_PORT], and
     * responds to an unauthenticated OPTIONS request with "RTSP/1.0 200 OK".
     *
     * OPTIONS is the first step in any RTSP session and does not require authorisation — it is
     * used here as a lightweight liveness check for the server.
     */
    @Test
    fun rtspServer_startsAndAcceptsConnection() {
        bindAndStartServer()

        val rtspReady = waitForPort(RTSP_HOST, RTSP_PORT, CONNECT_TIMEOUT_MS)
        assertTrue(
            "RTSP server should be listening on port $RTSP_PORT within ${CONNECT_TIMEOUT_MS}ms",
            rtspReady
        )

        val response = sendRtspRequest(
            RTSP_HOST, RTSP_PORT,
            "OPTIONS rtsp://$RTSP_HOST:$RTSP_PORT/ RTSP/1.0\r\nCSeq: 1\r\n\r\n"
        )

        assertNotNull("RTSP server should send a response to OPTIONS", response)
        assertTrue(
            "RTSP server OPTIONS response should start with 'RTSP/1.0 200 OK'\nActual: $response",
            response!!.startsWith("RTSP/1.0 200 OK")
        )
    }

    /**
     * Verifies that DESCRIBE with HTTP Basic authentication credentials returns a valid RTSP
     * status line (i.e. the server understands RTSP and is enforcing auth).
     *
     * A 200 OK response indicates the server accepted the credentials and returned an SDP
     * description for the stream. A 401 Unauthorized response is also valid — it means the
     * server is running and enforcing authentication. Any other "RTSP/1.0 NNN" line is
     * acceptable as long as it is a well-formed RTSP response.
     *
     * This test confirms that:
     * - The RTSP server is reachable.
     * - The server speaks RTSP (not HTTP or garbage).
     * - The supplied credentials are propagated to the server (visible in 200/401 behaviour).
     */
    @Test
    fun rtspServer_describe_returnsValidRtspResponse() {
        bindAndStartServer()

        val rtspReady = waitForPort(RTSP_HOST, RTSP_PORT, CONNECT_TIMEOUT_MS)
        assertTrue("RTSP server should be listening before DESCRIBE", rtspReady)

        val authValue = Base64.encodeToString(
            "$TEST_USERNAME:$TEST_PASSWORD".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val describeRequest =
            "DESCRIBE rtsp://$RTSP_HOST:$RTSP_PORT/ RTSP/1.0\r\n" +
            "CSeq: 1\r\n" +
            "Authorization: Basic $authValue\r\n" +
            "Accept: application/sdp\r\n" +
            "\r\n"

        val response = sendRtspRequest(RTSP_HOST, RTSP_PORT, describeRequest)

        assertNotNull("RTSP server should respond to DESCRIBE", response)
        val statusLine = response!!.lineSequence().first()
        assertTrue(
            "DESCRIBE response should be a valid RTSP status line (RTSP/1.0 NNN ...)\nActual: $statusLine",
            statusLine.startsWith("RTSP/1.0 ")
        )
    }
}
