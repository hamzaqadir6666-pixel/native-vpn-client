package com.lucentvpn.android.ui

import java.util.Locale
import kotlin.math.abs

/**
 * Display formatting for telemetry.
 *
 * Byte *sizes* use binary units (KiB/MiB) and byte *rates* use decimal units
 * (kbps/Mbps), because that is what each measurement conventionally means --
 * mixing them is how speed readouts end up 7% wrong.
 */
object Formatters {

    private val sizeUnits = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    /** e.g. "14.2 MiB". */
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 B"

        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < sizeUnits.lastIndex) {
            amount /= 1024.0
            unit++
        }

        return if (unit == 0) {
            "${amount.toLong()} ${sizeUnits[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", amount, sizeUnits[unit])
        }
    }

    /** e.g. "8.4 Mbps". Input is bytes per second. */
    fun rate(bytesPerSecond: Long): String {
        val bits = bytesPerSecond * 8.0

        return when {
            bits < 1_000 -> String.format(Locale.US, "%.0f bps", bits)
            bits < 1_000_000 -> String.format(Locale.US, "%.0f kbps", bits / 1_000)
            bits < 1_000_000_000 -> String.format(Locale.US, "%.1f Mbps", bits / 1_000_000)
            else -> String.format(Locale.US, "%.2f Gbps", bits / 1_000_000_000)
        }
    }

    /** Advertised relay throughput, which arrives already in bits/second. */
    fun advertisedSpeed(bitsPerSecond: Long): String = when {
        bitsPerSecond <= 0 -> "—"
        bitsPerSecond < 1_000_000 -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000.0)
        else -> String.format(Locale.US, "%.0f Mbps", bitsPerSecond / 1_000_000.0)
    }

    /** "04:31" under an hour, "1:04:31" beyond it. */
    fun duration(millis: Long): String {
        val total = abs(millis) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** Latency, or an em dash when we have no figure worth showing. */
    fun ping(millis: Int?): String =
        if (millis == null || millis <= 0) "—" else "$millis ms"

    /** Relative timestamp for the relay list header. */
    fun relativeTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "never"

        val delta = System.currentTimeMillis() - epochMillis
        return when {
            delta < 60_000 -> "just now"
            delta < 3_600_000 -> "${delta / 60_000} min ago"
            delta < 86_400_000 -> "${delta / 3_600_000} h ago"
            else -> "${delta / 86_400_000} d ago"
        }
    }

    /** Uptime the volunteer has sustained, coarse on purpose. */
    fun uptime(millis: Long): String = when {
        millis <= 0 -> "—"
        millis < 3_600_000 -> "${millis / 60_000}m"
        millis < 86_400_000 -> "${millis / 3_600_000}h"
        else -> "${millis / 86_400_000}d"
    }
}
