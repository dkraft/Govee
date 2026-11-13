package dev.offgrid.govee

/**
 * Saved tile order, with sensors that are new since the last drag appended.
 */
object TileOrder {
    fun arrange(incoming: List<String>, saved: List<String>): List<String> {
        val seen = incoming.toSet()
        val kept = saved.filter { it in seen }.distinct()
        val keptSet = kept.toSet()
        return kept + incoming.filter { it !in keptSet }
    }
}
