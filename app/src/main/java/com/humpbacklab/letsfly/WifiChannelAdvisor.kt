package com.humpbacklab.letsfly

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Estimates nearby AP interference from scan results; this is not airtime utilization. */
internal object WifiChannelAdvisor {
    data class AccessPoint(
        val frequencyMhz: Int,
        val signalDbm: Int,
        val widthMhz: Int = 20,
        val centerFrequencyMhz: Int = frequencyMhz
    )

    data class ChannelLoad(
        val channel: Int,
        val nearbyCount: Int,
        val interference: Double,
        val strongestSignalDbm: Int?
    )

    data class Assessment(
        val loads: List<ChannelLoad>,
        val recommendedChannel: Int?
    )

    fun assess(
        channels: List<Int>, accessPoints: List<AccessPoint>, currentChannel: Int?
    ): Assessment {
        val loads = channels.map { channel ->
            val center = frequencyMhz(channel)
            var nearbyCount = 0
            var interference = 0.0
            var strongestSignalDbm: Int? = null
            for (ap in accessPoints) {
                if (ap.frequencyMhz == center) nearbyCount++
                val apHalfWidth = max(11, ap.widthMhz / 2)
                val overlap = max(0, min(center + 11, ap.centerFrequencyMhz + apHalfWidth) -
                    max(center - 11, ap.centerFrequencyMhz - apHalfWidth))
                if (overlap > 0) {
                    strongestSignalDbm = max(strongestSignalDbm ?: Int.MIN_VALUE, ap.signalDbm)
                    // RSSI is logarithmic; cap the weight of exceptionally strong APs.
                    val signalWeight = 10.0.pow((ap.signalDbm + 80) / 20.0)
                        .coerceIn(0.1, 100.0)
                    interference += overlap / 22.0 * signalWeight
                }
            }
            ChannelLoad(channel, nearbyCount, interference, strongestSignalDbm)
        }
        if (loads.all { it.interference == 0.0 }) return Assessment(loads, null)

        // Recommend from the same supported channels and scores shown in the usage bars.
        val recommended = loads.minWithOrNull(compareBy<ChannelLoad> { it.interference }
            .thenBy { if (it.channel == currentChannel) 0 else 1 }
            .thenBy { it.channel })?.channel
        return Assessment(loads, recommended)
    }

    private fun frequencyMhz(channel: Int): Int =
        if (channel <= 13) 2407 + channel * 5 else 5000 + channel * 5
}
