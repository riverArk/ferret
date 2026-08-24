package io.riverark.ferret.core.security

class ForegroundLockPolicy(
    private val nowMillis: () -> Long,
    private val timeoutMillis: Long = FIVE_MINUTES_MILLIS,
) {
    private var backgroundedAtMillis: Long? = null

    fun backgrounded() {
        if (backgroundedAtMillis == null) backgroundedAtMillis = nowMillis()
    }

    fun foregrounded(): Boolean {
        val expired = shouldLock()
        backgroundedAtMillis = null
        return expired
    }

    fun shouldLock(): Boolean = backgroundedAtMillis?.let { nowMillis() - it >= timeoutMillis } == true

    fun millisUntilLock(): Long? = backgroundedAtMillis?.let {
        (timeoutMillis - (nowMillis() - it)).coerceAtLeast(0)
    }

    companion object {
        const val FIVE_MINUTES_MILLIS = 5 * 60 * 1_000L
    }
}
