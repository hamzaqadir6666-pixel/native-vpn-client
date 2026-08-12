package com.lucentvpn.android.data.model

/**
 * A single usable relay.
 *
 * A [VpnServer] is only ever created for an entry that carries a complete,
 * parseable OpenVPN configuration. Entries without one are dropped during
 * parsing, so every instance here is something we can genuinely dial.
 */
data class VpnServer(
    /** Stable identity across refreshes. VPN Gate host names are unique. */
    val id: String,
    val hostName: String,
    val ipAddress: String,
    val countryName: String,
    /** ISO 3166-1 alpha-2, e.g. "JP". Used for the flag glyph. */
    val countryCode: String,
    /** Latency reported by the source, in ms. -1 when unknown. */
    val reportedPingMs: Int,
    /** Latency we measured ourselves, in ms. Null until probed. */
    val measuredPingMs: Int? = null,
    /** Throughput reported by the source, in bits per second. */
    val speedBps: Long,
    /** Concurrent sessions currently on this relay. */
    val sessions: Int,
    /** How long the volunteer has kept this relay up, in ms. */
    val uptimeMs: Long,
    /** The operator's stated logging policy, verbatim from the source. */
    val logPolicy: String,
    val operator: String,
    /** Quality score reported by the source. Higher is better. */
    val score: Long,
    /** The full inline OpenVPN profile for this relay. */
    val openVpnConfig: String,
    /** Transport the bundled config actually dials. */
    val transport: Transport,
) {
    enum class Transport { UDP, TCP }

    /** Best latency figure we have, preferring our own measurement. */
    val effectivePingMs: Int
        get() = measuredPingMs ?: reportedPingMs

    val speedMbps: Double
        get() = speedBps / 1_000_000.0

    /**
     * Coarse 0..1 load estimate used for the UI bar. Derived from concurrent
     * sessions relative to the throughput the relay advertises, because VPN
     * Gate does not publish a capacity figure.
     */
    val load: Float
        get() {
            if (speedBps <= 0L) return 1f
            val capacity = (speedMbps / 2.0).coerceAtLeast(1.0)
            return (sessions / capacity).coerceIn(0.0, 1.0).toFloat()
        }

    val quality: Quality
        get() = when {
            effectivePingMs in 1..90 && speedMbps >= 12 -> Quality.EXCELLENT
            effectivePingMs in 1..180 && speedMbps >= 4 -> Quality.GOOD
            else -> Quality.FAIR
        }

    enum class Quality { EXCELLENT, GOOD, FAIR }

    /** Regional-indicator flag for [countryCode], or a globe when unknown. */
    val flagEmoji: String
        get() {
            if (countryCode.length != 2) return "\uD83C\uDF10"
            val base = 0x1F1E6
            val a = countryCode[0].uppercaseChar()
            val b = countryCode[1].uppercaseChar()
            if (a !in 'A'..'Z' || b !in 'A'..'Z') return "\uD83C\uDF10"
            return String(
                Character.toChars(base + (a - 'A')) + Character.toChars(base + (b - 'A'))
            )
        }
}
