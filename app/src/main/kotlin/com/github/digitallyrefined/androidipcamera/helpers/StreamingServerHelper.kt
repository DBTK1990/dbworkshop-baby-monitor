package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class StreamingServerHelper(
    private val context: Context,
    private val streamPort: Int = 4444,
    private val rtspPort: Int = 8554,
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val onControlCommand: (String, String) -> Unit = { _, _ -> }
) {
    private data class FailedAttempt(
        var count: Int = 0,
        var lastAttempt: Long = 0L,
        var blockedUntil: Long = 0L
    )

    private data class RtspClient(
        val sessionId: String,
        val socket: Socket,
        val outputStream: OutputStream,
        val rtpChannel: Int,
        val rtcpChannel: Int,
        val ssrc: Int,
        var sequenceNumber: Int = 0,
        var isPlaying: Boolean = false
    )

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var rtspServerSocket: ServerSocket? = null
    private var rtspServerJob: Job? = null
    @Volatile
    private var isStarting = false
    private val rtspClients = ConcurrentHashMap<String, RtspClient>()
    private val failedAttempts = ConcurrentHashMap<String, FailedAttempt>()
    @Volatile
    private var appInForeground: Boolean = true
    var webRtcManager: WebRtcManager? = null

    // SECURITY: Rate limiting constants (only for unauthenticated connections)
    private val MAX_FAILED_ATTEMPTS = 5  // 5 failed attempts allowed
    private val BLOCK_DURATION_MS = 15 * 60 * 1000L // 15 minutes block for unauthenticated
    private val RESET_WINDOW_MS = 10 * 60 * 1000L // 10 minutes reset window
    private val MAX_HTTP_HEADER_LINE_LENGTH = 8192

    // Connection limits
    private val SOCKET_TIMEOUT_MS = 60 * 1000 // 60 seconds socket timeout
    private val RTP_PAYLOAD_TYPE_JPEG = 26
    private val RTP_CLOCK_RATE = 90000
    private val RTP_MAX_PACKET_SIZE = 1400
    private val RTP_FIXED_HEADER_SIZE = 12
    private val RTP_JPEG_HEADER_SIZE = 8
    private val RTP_JPEG_OVERHEAD = RTP_FIXED_HEADER_SIZE + RTP_JPEG_HEADER_SIZE
    private val DEFAULT_JPEG_WIDTH = 640
    private val DEFAULT_JPEG_HEIGHT = 480
    private val JPEG_TYPE_BASELINE_DCT = 0x01
    private val JPEG_QUALITY_FACTOR = 75
    private val RTSP_SESSION_TIMEOUT_SECONDS = 60
    private val RTSP_SERVER_VERSION = "AndroidIPCamera/1.0"
    private val random = SecureRandom()

    fun hasAnyStreamingClients(): Boolean = rtspClients.values.any { it.isPlaying }
    fun hasAnyClients(): Boolean = hasAnyStreamingClients() || webRtcManager?.hasPeers() == true

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Check if currently blocked
        if (now < attempt.blockedUntil) {
            return true
        }

        // Reset counter if outside window
        if (now - attempt.lastAttempt > RESET_WINDOW_MS) {
            attempt.count = 0
        }

        return false
    }

    private fun recordFailedAttempt(clientIp: String) {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Prevent integer overflow
        if (attempt.count < Int.MAX_VALUE - 1) {
            attempt.count++
        }
        attempt.lastAttempt = now

        if (attempt.count >= MAX_FAILED_ATTEMPTS) {
            attempt.blockedUntil = now + BLOCK_DURATION_MS
            onLog("SECURITY: IP $clientIp blocked for ${BLOCK_DURATION_MS / (60 * 1000)} minutes due to too many unauthenticated attempts")
        }
    }

    fun startStreamingServer() {
        // Prevent concurrent starts
        synchronized(this) {
            if (isStarting) {
                return
            }

            // Check if server is already running
            if (serverJob != null && serverSocket != null && !serverSocket!!.isClosed) {
                return
            }

            isStarting = true
        }

        // Show toast when starting server
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Server starting...", Toast.LENGTH_SHORT).show()
        }

        // Stop existing server BEFORE creating new one (outside the coroutine)
        // This must be done to avoid cancelling the new job
        val oldJob: Job?
        val oldSocket: ServerSocket?
        val oldRtspJob: Job?
        val oldRtspSocket: ServerSocket?
        synchronized(this) {
            oldJob = serverJob
            oldSocket = serverSocket
            oldRtspJob = rtspServerJob
            oldRtspSocket = rtspServerSocket
            serverJob = null
            serverSocket = null
            rtspServerJob = null
            rtspServerSocket = null
        }

        // Stop old server and wait for it to fully stop (if it exists)
        if (oldJob != null || oldSocket != null || oldRtspJob != null || oldRtspSocket != null) {
            runBlocking(Dispatchers.IO) {
                try {
                    oldSocket?.close()
                } catch (e: IOException) {
                    onLog("Error closing old server socket: ${e.message}")
                }
                try {
                    oldRtspSocket?.close()
                } catch (e: IOException) {
                    onLog("Error closing old RTSP server socket: ${e.message}")
                }
                oldJob?.cancel()
                oldRtspJob?.cancel()
                try {
                    oldJob?.join()
                    oldRtspJob?.join()
                } catch (e: Exception) {
                    // Ignore cancellation exceptions
                }
                // Small delay to ensure port is released
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        synchronized(this) {
              serverJob = CoroutineScope(Dispatchers.IO).launch {
              try {
                  val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                  val secureStorage = SecureStorage(context)
                  val certificatePath = prefs.getString("certificate_path", null)

                  val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
                  val certificatePassword = rawPassword?.let {
                      if (it.isEmpty()) null else it.toCharArray()
                  }

                  // Certificate setup required - no defaults
                  var finalCertificatePath = certificatePath
                  var finalCertificatePassword = certificatePassword

                  if (certificatePath == null) {
                      // Use personal certificate from assets - requires password configuration
                      try {
                          val personalCertFile = File(context.filesDir, "personal_certificate.p12")
                          if (!personalCertFile.exists()) {
                              // Try to copy from assets first
                              try {
                                  context.assets.open("personal_certificate.p12").use { input ->
                                      personalCertFile.outputStream().use { output ->
                                          input.copyTo(output)
                                      }
                                  }
                              } catch (assetException: Exception) {
                                  // Certificate not in assets - provide helpful error
                                  Handler(Looper.getMainLooper()).post {
                                      onLog("Certificate not found.")
                                      Toast.makeText(context,
                                          "Certificate missing, reset app to generate a new certificate",
                                          Toast.LENGTH_LONG).show()
                                  }
                                  return@launch
                              }
                          }
                          finalCertificatePath = personalCertFile.absolutePath

                          // Require certificate password to be configured
                          if (finalCertificatePassword == null) {
                              return@launch
                          }

                      } catch (e: Exception) {
                          Handler(Looper.getMainLooper()).post {
                              onLog("ERROR: Could not load certificate: ${e.message}")
                              Toast.makeText(context, "Certificate error, check certificate file and password in Settings", Toast.LENGTH_LONG).show()
                          }
                          return@launch
                      }
                  }

                  val bindAddress = InetAddress.getByName("0.0.0.0")

                  serverSocket = try {
                      // Determine which certificate file to use
                      val certFile = if (certificatePath != null) {
                          // Custom certificate - copy from URI to local file
                          val uri = certificatePath.toUri()
                          val privateFile = File(context.filesDir, "certificate.p12")
                          if (privateFile.exists()) privateFile.delete()
                          context.contentResolver.openInputStream(uri)?.use { input ->
                              privateFile.outputStream().use { output ->
                                  input.copyTo(output)
                              }
                          } ?: throw IOException("Failed to open certificate file")
                          privateFile
                      } else {
                          // Personal certificate
                          File(finalCertificatePath!!)
                      }

                      certFile.inputStream().use { inputStream ->
                          try {
                              val keyStore = KeyStore.getInstance("PKCS12")
                              keyStore.load(inputStream, finalCertificatePassword)
                              val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                              keyManagerFactory.init(keyStore, finalCertificatePassword)
                              val sslContext = SSLContext.getInstance("TLSv1.3")
                              sslContext.init(keyManagerFactory.keyManagers, null, null)
                              val sslServerSocketFactory = sslContext.serverSocketFactory
                              (sslServerSocketFactory.createServerSocket(streamPort, 50, bindAddress) as SSLServerSocket).apply {
                                  reuseAddress = true
                                  enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                                  enabledCipherSuites = arrayOf(
                                      "TLS_AES_256_GCM_SHA384",
                                      "TLS_AES_128_GCM_SHA256",
                                      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                      "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
                                  )
                                  soTimeout = 30000
                              }
                          } catch (keystoreException: Exception) {
                              Handler(Looper.getMainLooper()).post {
                                  onLog("Certificate loading failed: ${keystoreException.message}")
                                  val errorMsg = when {
                                      keystoreException.message?.contains("password") == true ->
                                          "Certificate password is incorrect, check Settings > Advanced Security"
                                      keystoreException.message?.contains("keystore") == true ->
                                          "Certificate file is corrupted or invalid, regenerate with setup.bat"
                                      else ->
                                          "Certificate error: ${keystoreException.message}"
                                  }
                                  Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                              }
                              return@launch
                          }
                      }
                  } catch (e: Exception) {
                      Handler(Looper.getMainLooper()).post {
                          onLog("CRITICAL: Failed to create HTTPS server: ${e.message}")
                          Toast.makeText(context, "Failed to start secure HTTPS server: ${e.message}", Toast.LENGTH_LONG).show()
                      }
                      return@launch
                  }
                  onLog("Server started on port $streamPort (${if (certificatePath != null) "HTTPS" else "HTTP"})")
                  startRtspServer()
                  // Clear the starting flag now that server is running
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
                  Handler(Looper.getMainLooper()).post {
                      Toast.makeText(context, "Server started", Toast.LENGTH_SHORT).show()
                  }
                  while (isActive && !Thread.currentThread().isInterrupted) {
                      try {
                          val socket = serverSocket?.accept() ?: continue
                          val clientIp = socket.inetAddress.hostAddress

                          // Handle each connection in a separate coroutine to avoid blocking the accept loop
                          CoroutineScope(Dispatchers.IO).launch {
                              handleClientConnection(socket, clientIp)
                          }
                      } catch (e: IOException) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          // Ignore other connection errors
                      } catch (e: InterruptedException) {
                          Thread.currentThread().interrupt()
                          break
                      } catch (e: Exception) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          onLog("Unexpected error in server loop: ${e.message}")
                      }
                  }
              } catch (e: IOException) {
                  onLog("Could not start server: ${e.message}")
              } finally {
                  // Clear the starting flag if server failed to start
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
              }
            }
        }
    }

    private fun startRtspServer() {
        synchronized(this) {
            if (rtspServerJob != null && rtspServerSocket != null && !rtspServerSocket!!.isClosed) {
                return
            }
            rtspServerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bindAddress = InetAddress.getByName("0.0.0.0")
                    rtspServerSocket = ServerSocket(rtspPort, 50, bindAddress).apply {
                        reuseAddress = true
                        soTimeout = 30000
                    }
                    onLog("RTSP server started on port $rtspPort")
                    while (isActive && !Thread.currentThread().isInterrupted) {
                        try {
                            val socket = rtspServerSocket?.accept() ?: continue
                            val clientIp = socket.inetAddress.hostAddress
                            launch(Dispatchers.IO) {
                                handleRtspClientConnection(socket, clientIp)
                            }
                        } catch (e: IOException) {
                            if (rtspServerSocket == null || rtspServerSocket!!.isClosed) {
                                onLog("RTSP server socket closed")
                                break
                            }
                        } catch (e: Exception) {
                            if (rtspServerSocket == null || rtspServerSocket!!.isClosed) {
                                break
                            }
                            onLog("RTSP server loop error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    onLog("Failed to start RTSP server on port $rtspPort: ${e.message}")
                }
            }
        }
    }

    private fun handleRtspClientConnection(socket: Socket, clientIp: String) {
        var sessionId: String? = null
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            while (socket.isConnected && !socket.isClosed) {
                val requestLine = readHttpLine(inputStream) ?: break
                if (requestLine.isBlank()) continue
                val requestParts = requestLine.split(" ")
                if (requestParts.size < 2) break

                val method = requestParts[0].uppercase()
                val uri = requestParts[1]
                val headers = mutableListOf<String>()
                while (true) {
                    val line = readHttpLine(inputStream) ?: break
                    if (line.isEmpty()) break
                    headers.add(line)
                }

                val cSeq = headers.find { it.startsWith("CSeq:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?: "1"

                if (!isRtspAuthenticated(headers, uri, clientIp)) {
                    sendRtspResponse(
                        outputStream = outputStream,
                        statusLine = "RTSP/1.0 401 Unauthorized",
                        cSeq = cSeq,
                        headers = mapOf("WWW-Authenticate" to "Basic realm=\"Android IP Camera\"")
                    )
                    break
                }

                when (method) {
                    "OPTIONS" -> {
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq,
                            headers = mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
                        )
                    }
                    "DESCRIBE" -> {
                        if (!isRtspStreamUri(uri)) {
                            sendRtspResponse(outputStream, "RTSP/1.0 404 Not Found", cSeq)
                            continue
                        }
                        val sdp = buildRtspSdp(uri)
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq,
                            headers = mapOf(
                                "Content-Base" to "$uri/",
                                "Content-Type" to "application/sdp"
                            ),
                            body = sdp
                        )
                    }
                    "SETUP" -> {
                        if (!isRtspStreamUri(uri)) {
                            sendRtspResponse(outputStream, "RTSP/1.0 404 Not Found", cSeq)
                            continue
                        }
                        val transportHeader = headers.find { it.startsWith("Transport:", ignoreCase = true) }
                            ?.substringAfter(":")
                            ?.trim()
                            ?: ""
                        if (!transportHeader.contains("RTP/AVP/TCP", ignoreCase = true)) {
                            sendRtspResponse(outputStream, "RTSP/1.0 461 Unsupported Transport", cSeq)
                            continue
                        }
                        val (rtpChannel, rtcpChannel) = parseInterleavedChannels(transportHeader)
                        val currentSessionId = sessionId ?: java.util.UUID.randomUUID().toString()
                        val existingSession = rtspClients[currentSessionId]
                        val client = RtspClient(
                            sessionId = currentSessionId,
                            socket = socket,
                            outputStream = outputStream,
                            rtpChannel = rtpChannel,
                            rtcpChannel = rtcpChannel,
                            ssrc = existingSession?.ssrc ?: (random.nextInt() and Int.MAX_VALUE),
                            sequenceNumber = existingSession?.sequenceNumber ?: 0
                        )
                        rtspClients[currentSessionId] = client
                        sessionId = currentSessionId
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq,
                            headers = mapOf(
                                "Transport" to "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel;ssrc=${client.ssrc.toUInt().toString(16)}",
                                "Session" to "$currentSessionId;timeout=$RTSP_SESSION_TIMEOUT_SECONDS"
                            )
                        )
                    }
                    "PLAY" -> {
                        val playSession = headers.find { it.startsWith("Session:", ignoreCase = true) }
                            ?.substringAfter(":")
                            ?.trim()
                            ?.substringBefore(";")
                            ?: sessionId
                        val client = playSession?.let { rtspClients[it] }
                        if (client == null) {
                            sendRtspResponse(outputStream, "RTSP/1.0 454 Session Not Found", cSeq)
                            continue
                        }
                        val wasPlaying = client.isPlaying
                        client.isPlaying = true
                        sessionId = client.sessionId
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq,
                            headers = mapOf(
                                "Session" to client.sessionId,
                                "RTP-Info" to "url=$uri;seq=${client.sequenceNumber};rtptime=${currentRtpTimestamp()}"
                            )
                        )
                        if (!wasPlaying) {
                            onClientConnected()
                        }
                    }
                    "GET_PARAMETER" -> {
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq,
                            headers = sessionId?.let { mapOf("Session" to it) } ?: emptyMap()
                        )
                    }
                    "TEARDOWN" -> {
                        val teardownSession = headers.find { it.startsWith("Session:", ignoreCase = true) }
                            ?.substringAfter(":")
                            ?.trim()
                            ?.substringBefore(";")
                            ?: sessionId
                        teardownSession?.let { rtspClients.remove(it) }
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 200 OK",
                            cSeq = cSeq
                        )
                        break
                    }
                    else -> {
                        sendRtspResponse(
                            outputStream = outputStream,
                            statusLine = "RTSP/1.0 405 Method Not Allowed",
                            cSeq = cSeq,
                            headers = mapOf("Allow" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            onLog("RTSP client error from $clientIp: ${e.message}")
        } finally {
            sessionId?.let {
                val wasPlaying = rtspClients.remove(it)?.isPlaying == true
                if (wasPlaying && !hasAnyStreamingClients() && webRtcManager?.hasPeers() != true) {
                    onClientDisconnected()
                }
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    fun broadcastFrame(jpegBytes: ByteArray) {
        sendRtspJpegFrame(jpegBytes)
    }

    private fun isRtspAuthenticated(headers: List<String>, requestUri: String, clientIp: String): Boolean {
        val secureStorage = SecureStorage(context)
        val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
        val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""
        val username = rawUsername.trim()
        val password = rawPassword
        if (!InputValidator.isValidUsername(username) || !InputValidator.isValidPassword(password)) {
            recordFailedAttempt(clientIp)
            onLog("SECURITY: RTSP connection rejected - credentials not configured")
            return false
        }

        val authValue = headers.find { it.startsWith("Authorization:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
        val decodedAuth = when {
            authValue != null && authValue.startsWith("Basic ", ignoreCase = true) -> {
                try {
                    String(Base64.decode(authValue.substringAfter("Basic ", ""), Base64.DEFAULT))
                } catch (_: Exception) {
                    recordFailedAttempt(clientIp)
                    return false
                }
            }
            authValue != null -> {
                recordFailedAttempt(clientIp)
                return false
            }
            else -> parseRtspUriUserInfo(requestUri) ?: return false
        }
        val valid = decodedAuth == "$username:$password"
        if (!valid) {
            recordFailedAttempt(clientIp)
            onLog("SECURITY: Failed RTSP auth attempt from $clientIp")
        }
        return valid
    }

    private fun parseRtspUriUserInfo(requestUri: String): String? {
        if (!requestUri.startsWith("rtsp://", ignoreCase = true)) {
            return null
        }
        return try {
            val userInfo = URI(requestUri).userInfo ?: return null
            if (!userInfo.contains(":")) return null

            val uriUsername = URLDecoder.decode(userInfo.substringBefore(":"), Charsets.UTF_8.name()).trim()
            val uriPassword = URLDecoder.decode(userInfo.substringAfter(":", ""), Charsets.UTF_8.name())
            if (uriPassword.isEmpty()) return null
            if (!InputValidator.isValidUsername(uriUsername) || !InputValidator.isValidPassword(uriPassword)) {
                return null
            }
            "$uriUsername:$uriPassword"
        } catch (_: Exception) {
            onLog("SECURITY: Rejected malformed RTSP URI credentials")
            null
        }
    }

    private fun sendRtspResponse(
        outputStream: OutputStream,
        statusLine: String,
        cSeq: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ) {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val response = StringBuilder().apply {
            append(statusLine).append("\r\n")
            append("CSeq: ").append(cSeq).append("\r\n")
            append("Server: $RTSP_SERVER_VERSION\r\n")
            headers.forEach { (key, value) ->
                append(key).append(": ").append(value).append("\r\n")
            }
            if (bodyBytes != null) {
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            append("\r\n")
        }.toString().toByteArray(Charsets.UTF_8)
        outputStream.write(response)
        if (bodyBytes != null) {
            outputStream.write(bodyBytes)
        }
        outputStream.flush()
    }

    private fun parseInterleavedChannels(transportHeader: String): Pair<Int, Int> {
        val interleaved = transportHeader
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("interleaved=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.split("-")
            ?.mapNotNull { it.toIntOrNull() }
        return if (interleaved != null && interleaved.size == 2) {
            interleaved[0] to interleaved[1]
        } else {
            0 to 1
        }
    }

    private fun isRtspStreamUri(uri: String): Boolean {
        val path = if (uri.startsWith("rtsp://", ignoreCase = true)) {
            val slashIndex = uri.indexOf('/', startIndex = 7)
            if (slashIndex == -1) "/" else uri.substring(slashIndex)
        } else {
            uri
        }
        return path.startsWith("/stream")
    }

    private fun buildRtspSdp(uri: String): String {
        val normalizedUri = uri.substringBefore("?")
        return """
v=0
o=- 0 0 IN IP4 0.0.0.0
s=Android IP Camera
t=0 0
a=control:*
m=video 0 RTP/AVP $RTP_PAYLOAD_TYPE_JPEG
a=rtpmap:$RTP_PAYLOAD_TYPE_JPEG JPEG/$RTP_CLOCK_RATE
a=control:$normalizedUri
        """.trimIndent() + "\r\n"
    }

    private fun sendRtspJpegFrame(jpegBytes: ByteArray) {
        val playingClients = rtspClients.values.filter { it.isPlaying }
        if (playingClients.isEmpty()) {
            return
        }

        val dimensions = getJpegDimensions(jpegBytes)
        val widthBlocks = ((dimensions.first + 7) / 8).coerceIn(0, 255)
        val heightBlocks = ((dimensions.second + 7) / 8).coerceIn(0, 255)
        val timestamp = currentRtpTimestamp()
        val maxPayload = RTP_MAX_PACKET_SIZE - RTP_JPEG_OVERHEAD
        val clientsToRemove = mutableListOf<String>()

        playingClients.forEach { client ->
            try {
                var offset = 0
                while (offset < jpegBytes.size) {
                    val fragmentLength = minOf(maxPayload, jpegBytes.size - offset)
                    val marker = offset + fragmentLength >= jpegBytes.size
                    val packet = buildRtpJpegPacket(
                        jpegBytes = jpegBytes,
                        offset = offset,
                        length = fragmentLength,
                        marker = marker,
                        sequenceNumber = client.sequenceNumber,
                        timestamp = timestamp,
                        ssrc = client.ssrc,
                        widthBlocks = widthBlocks,
                        heightBlocks = heightBlocks
                    )
                    sendInterleavedPacket(client.outputStream, client.rtpChannel, packet)
                    client.sequenceNumber = (client.sequenceNumber + 1) and 0xFFFF
                    offset += fragmentLength
                }
            } catch (e: Exception) {
                clientsToRemove.add(client.sessionId)
            }
        }

        clientsToRemove.forEach { session ->
            val removed = rtspClients.remove(session)
            try {
                removed?.socket?.close()
            } catch (_: Exception) {
            }
            if (removed?.isPlaying == true && !hasAnyStreamingClients() && webRtcManager?.hasPeers() != true) {
                onClientDisconnected()
            }
        }
    }

    private fun buildRtpJpegPacket(
        jpegBytes: ByteArray,
        offset: Int,
        length: Int,
        marker: Boolean,
        sequenceNumber: Int,
        timestamp: Int,
        ssrc: Int,
        widthBlocks: Int,
        heightBlocks: Int
    ): ByteArray {
        val packet = ByteArray(RTP_JPEG_OVERHEAD + length)
        packet[0] = 0x80.toByte()
        packet[1] = (((if (marker) 0x80 else 0x00) or RTP_PAYLOAD_TYPE_JPEG)).toByte()
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        packet[12] = 0x00
        packet[13] = ((offset shr 16) and 0xFF).toByte()
        packet[14] = ((offset shr 8) and 0xFF).toByte()
        packet[15] = (offset and 0xFF).toByte()
        packet[16] = JPEG_TYPE_BASELINE_DCT.toByte()
        packet[17] = JPEG_QUALITY_FACTOR.toByte()
        packet[18] = widthBlocks.toByte()
        packet[19] = heightBlocks.toByte()
        System.arraycopy(jpegBytes, offset, packet, 20, length)
        return packet
    }

    private fun sendInterleavedPacket(outputStream: OutputStream, channel: Int, packet: ByteArray) {
        val length = packet.size
        outputStream.write(byteArrayOf(
            '$'.code.toByte(),
            channel.toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        ))
        outputStream.write(packet)
        outputStream.flush()
    }

    private fun getJpegDimensions(jpegBytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
        val width = if (options.outWidth > 0) options.outWidth else DEFAULT_JPEG_WIDTH
        val height = if (options.outHeight > 0) options.outHeight else DEFAULT_JPEG_HEIGHT
        return width to height
    }

    private fun currentRtpTimestamp(): Int {
        return (System.nanoTime() * RTP_CLOCK_RATE / 1_000_000_000L).toInt()
    }

    private suspend fun handleClientConnection(socket: Socket, clientIp: String) {
        try {
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            val writer = PrintWriter(outputStream, true)

            // Configure socket timeouts for security
            socket.soTimeout = SOCKET_TIMEOUT_MS

            // Read the request line (e.g., GET /stream HTTP/1.1)
            val requestLine = readHttpLine(inputStream) ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return
            val uri = requestParts[1]

            val secureStorage = SecureStorage(context)
            val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
            val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""

            // SECURITY: Authentication is now MANDATORY for all connections
            // Validate stored credentials - require both username and password
            val username = rawUsername.trim()
            val password = rawPassword

            if (!InputValidator.isValidUsername(username) || !InputValidator.isValidPassword(password)) {
                // CRITICAL: No valid credentials configured - reject all connections
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 403 Forbidden\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("SECURITY ERROR: Authentication credentials not properly configured.\r\n")
                writer.print("Configure username and password in app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Connection rejected - authentication credentials not configured")
                return
            }

            // Read HTTP headers
            val headers = mutableListOf<String>()
            while (true) {
                val line = readHttpLine(inputStream) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            // SECURITY: Require Basic Authentication header for all requests
            // Parse headers in a robust, case-insensitive way (RFC 7230: header field names are case-insensitive)
            val authHeaderPair = headers.mapNotNull { hdr ->
                val idx = hdr.indexOf(":")
                if (idx == -1) return@mapNotNull null
                val name = hdr.substring(0, idx).trim()
                val value = hdr.substring(idx + 1).trim()
                name to value
            }.find { (name, value) ->
                name.equals("Authorization", ignoreCase = true) && value.startsWith("Basic ", ignoreCase = true)
            }

            if (authHeaderPair == null) {
                // Rate limiting ONLY applies to unauthenticated requests
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for unauthenticated
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited unauthenticated request from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                return
            }

            val authValue = authHeaderPair.second
            val providedAuthEncoded = authValue.substringAfter("Basic ", "")
            val providedAuth = try {
                val decoded = Base64.decode(providedAuthEncoded, Base64.DEFAULT)
                String(decoded)
            } catch (e: IllegalArgumentException) {
                // Malformed base64
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited malformed auth attempt from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Failed authentication attempt from $clientIp (malformed base64)")
                return
            }

            if (providedAuth != "$username:$password") {
                // Rate limiting ONLY applies to failed authentication attempts
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for failed auth
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited failed auth attempt from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Failed authentication attempt from $clientIp")
                return
            }

            // Handle Control UI and Commands
            if (uri == "/" || uri == "") {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val curCamera = prefs.getString("last_camera_facing", "back") ?: "back"
                val curResolution = prefs.getString("camera_resolution", "low") ?: "low"
                val curZoom = prefs.getString("camera_zoom", "1.0") ?: "1.0"
                val curScale = prefs.getString("stream_scale", "1.0") ?: "1.0"
                val curExposure = prefs.getString("camera_exposure", "0") ?: "0"
                val curContrast = prefs.getString("camera_contrast", "0") ?: "0"
                val curDelay = prefs.getString("stream_delay", "33") ?: "33"
                val curTorch = prefs.getString("camera_torch", "off") ?: "off"

                val htmlTemplate = try {
                    context.assets.open("index.html").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "<html><body>Error loading interface.</body></html>"
                }

                val htmlResponse = htmlTemplate
                    .replace("{{CUR_CAMERA}}", curCamera)
                    .replace("{{RES_LOW_SELECTED}}", if (curResolution == "low") "selected" else "")
                    .replace("{{RES_MEDIUM_SELECTED}}", if (curResolution == "medium") "selected" else "")
                    .replace("{{RES_HIGH_SELECTED}}", if (curResolution == "high") "selected" else "")
                    .replace("{{CUR_ZOOM}}", curZoom)
                    .replace("{{CUR_SCALE}}", curScale)
                    .replace("{{CUR_EXPOSURE}}", curExposure)
                    .replace("{{CUR_CONTRAST}}", curContrast)
                    .replace("{{CUR_DELAY}}", curDelay)
                    .replace("{{CUR_TORCH}}", curTorch)

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(htmlResponse)
                writer.flush()
                socket.close()
                return
            }

            if (uri.contains("?")) {
                val query = uri.substringAfter("?")
                query.split("&").forEach { param ->
                    val keyValue = param.split("=")
                    if (keyValue.size == 2) {
                        onControlCommand(keyValue[0], keyValue[1])
                    }
                }
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nOK")
                writer.flush()
                socket.close()
                return
            }

            if (uri == "/openapi.json") {
                val openApiJson = try {
                    context.assets.open("openapi.json").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                if (openApiJson == null) {
                    writer.print("HTTP/1.1 404 Not Found\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Not Found\r\n")
                } else {
                    val bytes = openApiJson.toByteArray(Charsets.UTF_8)
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Content-Type: application/json\r\n")
                    writer.print("Content-Length: ${bytes.size}\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    outputStream.write(bytes)
                    outputStream.flush()
                }
                socket.close()
                return
            }

            if (uri == "/webrtc/offer" && requestParts[0] == "OPTIONS") {
                writer.print("HTTP/1.1 204 No Content\r\n")
                writer.print("Allow: OPTIONS, POST\r\n")
                writer.print("Access-Control-Allow-Origin: *\r\n")
                writer.print("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
                writer.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                socket.close()
                return
            }

            // WebRTC signaling: POST /webrtc/offer — browser sends SDP offer, Android replies with SDP answer
            if (uri == "/webrtc/offer" && requestParts[0] == "POST") {
                val contentLength = headers
                    .find { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                if (contentLength <= 0) {
                    writer.print("HTTP/1.1 400 Bad Request\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Content-Length must be greater than zero for /webrtc/offer\r\n")
                    writer.flush()
                    socket.close()
                    return
                }
                val bodyBytes = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = inputStream.read(bodyBytes, totalRead, contentLength - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                if (totalRead != contentLength) {
                    writer.print("HTTP/1.1 400 Bad Request\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Incomplete request body\r\n")
                    writer.flush()
                    socket.close()
                    return
                }
                val body = String(bodyBytes, 0, totalRead, Charsets.UTF_8)

                val contentType = headers
                    .find { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
                val useJsonResponse = contentType.startsWith("application/json", ignoreCase = true)

                val offerSdp = if (useJsonResponse) {
                    try {
                        org.json.JSONObject(body).getString("sdp")
                    } catch (e: Exception) {
                        writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInvalid JSON")
                        writer.flush()
                        socket.close()
                        return
                    }
                } else {
                    // Raw SDP body (application/sdp, text/plain, or go2rtc format)
                    body.trim()
                }

                val sessionId = java.util.UUID.randomUUID().toString()
                val answerSdp = try {
                    webRtcManager?.handleOffer(sessionId, offerSdp)
                        ?: throw IllegalStateException("WebRTC not initialized")
                } catch (e: Exception) {
                    onLog("WebRTC offer error: ${e.message}")
                    writer.print("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nWebRTC signaling failed")
                    writer.flush()
                    socket.close()
                    return
                }

                val responseBytes = if (useJsonResponse) {
                    org.json.JSONObject()
                        .put("sdp", answerSdp)
                        .put("type", "answer")
                        .put("sessionId", sessionId)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                } else {
                    answerSdp.toByteArray(Charsets.UTF_8)
                }
                val responseContentType = if (useJsonResponse) "application/json" else "application/sdp"

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: $responseContentType\r\n")
                writer.print("Content-Length: ${responseBytes.size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                outputStream.write(responseBytes)
                outputStream.flush()
                socket.close()
                return
            }

            if (uri.startsWith("/audio")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                    return
                }
                try {
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Connection: keep-alive\r\n")
                    writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    writer.print("Pragma: no-cache\r\n")
                    writer.print("Expires: 0\r\n")
                    writer.print("Content-Type: audio/wav\r\n")
                    writer.print("Transfer-Encoding: chunked\r\n\r\n")
                    writer.flush()

                    val sampleRate = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_MONO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    if (minBuffer <= 0) {
                        throw IOException("Invalid AudioRecord buffer size: $minBuffer")
                    }
                    val bufferSize = minBuffer * 2
                    val audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    val wavHeader = createWavHeader(
                        sampleRate = sampleRate,
                        bitsPerSample = 16,
                        channels = 1
                    )
                    writeChunk(outputStream, wavHeader, wavHeader.size)

                    audioRecord.startRecording()
                    val pcmBuffer = ByteArray(bufferSize)
                    try {
                        while (socket.isConnected && !socket.isClosed && appInForeground) {
                            val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                            if (read <= 0) continue
                            writeChunk(outputStream, pcmBuffer, read)
                        }
                    } finally {
                        try {
                            audioRecord.stop()
                        } catch (_: Exception) {
                        }
                        audioRecord.release()
                        try {
                            outputStream.write("0\r\n\r\n".toByteArray())
                            outputStream.flush()
                        } catch (_: Exception) {
                        }
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (sec: SecurityException) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    onLog("Audio stream error: ${e.message}")
                    try {
                        writer.print("HTTP/1.1 500 Internal Server Error\r\n")
                        writer.print("Content-Type: text/plain\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.print("Audio streaming failed.\r\n")
                        writer.flush()
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                return
            }

            } else {
                writer.print("HTTP/1.1 404 Not Found\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Not Found\r\n")
                writer.flush()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            onLog("Error handling client connection from $clientIp: ${e.message}")
            try {
                socket.close()
            } catch (closeException: Exception) {
                // Ignore
            }
        }
    }

    private fun readHttpLine(inputStream: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val byte = inputStream.read()
            if (byte == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) {
                if (buffer.size() >= MAX_HTTP_HEADER_LINE_LENGTH) {
                    throw IOException("HTTP header line too long")
                }
                buffer.write(byte)
            }
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private fun createWavHeader(sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataChunkSize = 0x7FFFFFFF // Placeholder large size for live stream
        val riffChunkSize = 36 + dataChunkSize

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(riffChunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataChunkSize)
        return buffer.array()
    }

    private fun writeChunk(outputStream: OutputStream, data: ByteArray, length: Int) {
        val header = length.toString(16) + "\r\n"
        outputStream.write(header.toByteArray())
        outputStream.write(data, 0, length)
        outputStream.write("\r\n".toByteArray())
        outputStream.flush()
    }

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    suspend fun stopStreamingServer() {
        val jobToCancel: Job?
        val socketToClose: ServerSocket?
        val rtspJobToCancel: Job?
        val rtspSocketToClose: ServerSocket?

        synchronized(this) {
            // Get references to close outside synchronized block
            jobToCancel = serverJob
            socketToClose = serverSocket
            rtspJobToCancel = rtspServerJob
            rtspSocketToClose = rtspServerSocket
            serverSocket = null
            serverJob = null
            rtspServerSocket = null
            rtspServerJob = null
        }

        // If there's nothing to stop, return immediately
        if (jobToCancel == null && socketToClose == null && rtspJobToCancel == null && rtspSocketToClose == null) {
            return
        }

        // Run socket closing operations on background thread to avoid NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
            // Close the server socket first to interrupt any blocking accept() calls
            try {
                socketToClose?.close()
            } catch (e: IOException) {
                onLog("Error closing server socket: ${e.message}")
            }
            try {
                rtspSocketToClose?.close()
            } catch (e: IOException) {
                onLog("Error closing RTSP server socket: ${e.message}")
            }

            // Cancel the server coroutine and wait for it to finish
            jobToCancel?.cancel()
            rtspJobToCancel?.cancel()
            try {
                // Wait for the coroutine to finish
                jobToCancel?.join()
                rtspJobToCancel?.join()
            } catch (e: Exception) {
                onLog("Error waiting for server job: ${e.message}")
            }

            // Close all client connections (this involves network operations)
            closeClientConnection()

            // Wait a bit longer to ensure port is fully released
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

        }
    }

    fun closeClientConnection() {
        rtspClients.values.forEach { client ->
            try {
                client.socket.close()
            } catch (e: IOException) {
                onLog("Error closing RTSP client connection: ${e.message}")
            }
        }
        rtspClients.clear()
        if (!hasAnyStreamingClients()) {
            onClientDisconnected()
        }
    }
}
