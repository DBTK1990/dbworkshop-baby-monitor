package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

object CameraRegistrationHelper {
    private const val TAG = "CameraRegistration"
    private const val PREF_REGISTRATION_URL = "registration_url"
    private const val PREF_BYPASS_SSL       = "registration_bypass_ssl"

    /**
     * Trust-all TrustManager — used exclusively for the registration endpoint so that
     * self-signed or internal CA certificates on the cluster side are accepted without
     * requiring the device's system trust store.
     */
    private val trustAllCerts: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").also { ctx ->
            ctx.init(null, trustAllCerts, SecureRandom())
        }
    }

    private val acceptAllHostnames = HostnameVerifier { _, _ -> true }

    fun register(context: Context, ip: String) {
        val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
        val url     = prefs.getString(PREF_REGISTRATION_URL, "").orEmpty().trim()
        val token   = SecureStorage(context).getSecureString(SecureStorage.KEY_REGISTRATION_TOKEN, "").orEmpty().trim()
        val bypassSsl = prefs.getBoolean(PREF_BYPASS_SSL, false)
        if (url.isBlank() || token.isBlank()) return

        thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                // Apply trust-all SSL only when the user has explicitly opted in via Settings.
                if (bypassSsl && conn is HttpsURLConnection) {
                    conn.sslSocketFactory = trustAllSslContext.socketFactory
                    conn.hostnameVerifier  = acceptAllHostnames
                    AppLogger.w(TAG, "SSL verification disabled for registration endpoint (bypass enabled in Settings)")
                }
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val body = JSONObject().put("ip", ip).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(body) }

                val code = conn.responseCode
                if (code in 200..299) {
                    conn.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    AppLogger.i(TAG, "Registration HTTP $code for IP $ip")
                } else {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    AppLogger.w(TAG, "Registration HTTP $code for IP $ip")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Registration failed for IP $ip: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
