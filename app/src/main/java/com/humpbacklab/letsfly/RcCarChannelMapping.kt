package com.humpbacklab.letsfly

/** Maps a centered steering or throttle axis to the configured channel travel. */
object RcCarChannelMapping {
    fun duty(axis: Float, rangePercent: Int): Float =
        0.5f + axis.coerceIn(-1f, 1f) * rangePercent.coerceIn(0, 100) / 200f
}
