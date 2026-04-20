package com.github.digitallyrefined.androidipcamera.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.helpers.LogBuffer

class LogsActivity : AppCompatActivity() {

    private lateinit var logsTextView: TextView
    private lateinit var logsScrollView: ScrollView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLogs()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        supportActionBar?.title = "Logs"

        logsTextView = findViewById(R.id.logsTextView)
        logsScrollView = findViewById(R.id.logsScrollView)

        findViewById<Button>(R.id.copyLogsButton).setOnClickListener {
            val text = LogBuffer.getAll().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            LogBuffer.clear()
            refreshLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshLogs() {
        val entries = LogBuffer.getAll()
        if (entries.isEmpty()) {
            logsTextView.text = "No logs yet."
        } else {
            logsTextView.text = entries.joinToString("\n")
            logsScrollView.post { logsScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
