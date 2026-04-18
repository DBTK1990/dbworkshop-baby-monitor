package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

object CameraRegistrationHelper {
    private const val TAG = "CameraRegistration"
    private const val PREF_REGISTRATION_URL = "registration_url"

    fun register(context: Context, ip: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val url = prefs.getString(PREF_REGISTRATION_URL, "").orEmpty()
        val token = SecureStorage(context).getSecureString(SecureStorage.KEY_REGISTRATION_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) return

        thread {
            var conn: HttpsURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val requestBody = JSONObject().put("ip", ip).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(requestBody) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    // Consume body so underlying socket can be reused/closed cleanly.
                    conn.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } else {
                    // Consume error body for the same reason as success path.
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }

                Log.i(TAG, "Registration HTTP $responseCode for IP $ip")
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed for IP $ip", e)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
