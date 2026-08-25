package io.riverark.ferret.core.security

class SensitiveContentCounter {
    private var count = 0

    fun update(visible: Boolean): Boolean {
        count = (count + if (visible) 1 else -1).coerceAtLeast(0)
        return count > 0
    }
}
