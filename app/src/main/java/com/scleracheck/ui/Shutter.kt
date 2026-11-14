package com.scleracheck.ui

/**
 * Manual shutter from the phone's volume keys (MainActivity forwards them while a check screen is
 * open). Lets a helper holding the phone with the back camera trigger capture without looking at
 * the screen. The press is only a trigger: the captured frames still go through the full quality gate.
 */
object Shutter {
    /** Set by the active check session; null when no check is running (volume keys behave normally). */
    @Volatile var listener: (() -> Unit)? = null

    /** Returns true if the press was consumed. */
    fun press(): Boolean {
        val l = listener ?: return false
        l()
        return true
    }
}
