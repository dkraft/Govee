package dev.offgrid.govee.store

import java.util.concurrent.CopyOnWriteArraySet

/**
 * The phone uploads a sensor only when the Pi has not bound it to the H5151.
 * [shouldKeep] is the rule. [BoundDevices] is the set the scan last fetched.
 */
object PhoneUpload {
    fun shouldKeep(address: String, bound: Set<String>): Boolean = address !in bound
}

object BoundDevices {
    private val addresses = CopyOnWriteArraySet<String>()

    fun replace(next: Set<String>) {
        addresses.clear()
        addresses.addAll(next)
    }

    fun contains(address: String): Boolean = address in addresses

    fun snapshot(): Set<String> = addresses.toSet()
}
