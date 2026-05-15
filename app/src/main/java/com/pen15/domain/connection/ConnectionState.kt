package com.pen15.domain.connection

/**
 * Single source of truth for the hardware status that the UI observes.
 *
 *  Idle         — service is up but no USB device is plugged in.
 *  Searching    — at least one device enumerated, opening / pinging.
 *  FlipperOnly  — Flipper FAP is alive and has acknowledged a JSON ping.
 *  AwokOnly     — AWOK direct USB serial is alive (no Flipper).
 *  Both         — Flipper FAP + AWOK direct both alive.
 *  Error        — terminal failure with a human-readable cause.
 */
sealed class ConnectionState(val message: String) {
    object Idle                                         : ConnectionState("Plug in your hardware to get started.")
    data class Searching(val what: String)              : ConnectionState("Looking for $what…")
    data class FlipperOnly(val firmware: String)        : ConnectionState("Flipper Zero is ready.")
    data class AwokOnly(val chipset: String)            : ConnectionState("AWOK is ready.")
    data class Both(val firmware: String, val chipset: String) :
        ConnectionState("Flipper + AWOK both ready.")
    data class Error(val cause: String, val canRetry: Boolean = true) : ConnectionState(cause)

    val flipperReady: Boolean get() = this is FlipperOnly || this is Both
    val awokReady:    Boolean get() = this is AwokOnly    || this is Both
}
