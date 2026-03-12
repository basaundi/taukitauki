package com.example.flickkeyboard

enum class FlickDirection {
    TAP, UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
}

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
    val is8Way: Boolean 
        get() = ul != null || ur != null || dl != null || dr != null
}
