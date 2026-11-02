package com.humpbacklab.letsfly

/** One startup-only fallback; valid USB telemetry locks the current baud for the session. */
internal class ElrsBaudFallback(
    private val allowFallback: () -> Boolean,
    private val switchToLegacy: () -> Unit
) {
    companion object { const val WAIT_MS = 8_000L }

    var active = false
        private set
    private var deadline = 0L

    fun start(nowMs: Long, highBaud: Boolean) {
        deadline = nowMs + WAIT_MS
        active = highBaud && allowFallback()
    }

    fun receive() { active = false }

    fun tick(nowMs: Long) {
        if (!active) return
        if (!allowFallback()) { close(); return }
        if (nowMs >= deadline) {
            close() // Do not retry, including when setting the legacy baud fails.
            switchToLegacy()
        }
    }

    fun close() { active = false }
}
