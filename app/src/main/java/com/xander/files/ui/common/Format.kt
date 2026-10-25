package com.xander.files.ui.common

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Format {
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        val digit = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(1, units.size)
        val v = bytes / 1024.0.pow(digit)
        return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[digit - 1])
    }

    fun date(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun shortDate(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun relative(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun speed(bps: Long) = size(bps) + "/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) return "--"
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
