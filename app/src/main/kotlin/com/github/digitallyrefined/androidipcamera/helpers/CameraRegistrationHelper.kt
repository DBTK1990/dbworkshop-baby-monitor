package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
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
            try {
                val conn = URL(url).openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.outputStream.use { it.write("""{"ip":"$ip"}""".toByteArray()) }
                Log.i(TAG, "Registration HTTP ${conn.responseCode} for IP $ip")
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed for IP $ip", e)
            }
        }
    }
}
