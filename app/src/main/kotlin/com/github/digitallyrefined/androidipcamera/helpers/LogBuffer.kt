package com.github.digitallyrefined.androidipcamera.helpers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(message: String) {
        val entry = "[${fmt.format(Date())}] $message"
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun getAll(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }
}
