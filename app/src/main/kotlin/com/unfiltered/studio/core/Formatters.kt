package com.unfiltered.studio.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Formatters {

    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "Unknown size"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val exp = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val scaled = value / 1024.0.pow(exp.toDouble())
        return String.format(Locale.US, if (scaled >= 100) "%.0f %s" else "%.1f %s", scaled, units[exp - 1])
    }

    fun count(value: Long?): String {
        if (value == null) return "–"
        val a = abs(value)
        return when {
            a >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1e9)
            a >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1e6)
            a >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1e3)
            else -> value.toString()
        }
    }

    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "–"
        val s = ms / 1000.0
        return when {
            s < 60 -> String.format(Locale.US, "%.1f s", s)
            s < 3600 -> String.format(Locale.US, "%dm %02ds", (s / 60).toInt(), (s % 60).toInt())
            else -> String.format(Locale.US, "%dh %02dm", (s / 3600).toInt(), ((s % 3600) / 60).toInt())
        }
    }

    fun dateTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

    fun date(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

    fun relative(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - epochMs).coerceAtLeast(0) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86_400 -> "${diff / 3600} h ago"
            diff < 7 * 86_400 -> "${diff / 86_400} d ago"
            else -> date(epochMs)
        }
    }

    fun decimal(value: Float, digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", value)
}
