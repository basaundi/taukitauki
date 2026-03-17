package eus.basaundi.zirkimako

import kotlin.math.abs

data class Gesture(val isTap: Boolean, val angle: Double = 0.0)

data class FlickKey(
    val tap: String? = null,
    val up: String? = null,
    val down: String? = null,
    val left: String? = null,
    val right: String? = null,
    val ul: String? = null,
    val ur: String? = null,
    val dl: String? = null,
    val dr: String? = null
) {
    fun getChar(gesture: Gesture): String? {
        if (gesture.isTap) return tap
        
        val options = mutableListOf<Pair<Double, String>>()
        right?.let { options.add(0.0 to it) }
        dr?.let { options.add(45.0 to it) }
        down?.let { options.add(90.0 to it) }
        dl?.let { options.add(135.0 to it) }
        left?.let { options.add(180.0 to it) }
        ul?.let { options.add(225.0 to it) }
        up?.let { options.add(270.0 to it) }
        ur?.let { options.add(315.0 to it) }
        
        if (options.isEmpty()) return tap

        // Find the direction with the minimum angular distance
        return options.minByOrNull { (angle, _) ->
            val diff = abs(gesture.angle - angle) % 360
            val normalizedDiff = if (diff > 180) 360 - diff else diff
            normalizedDiff
        }?.second
    }

    val is8Way: Boolean 
        get() = ul != null || ur != null || dl != null || dr != null
}
