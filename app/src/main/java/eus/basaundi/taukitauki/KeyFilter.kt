package eus.basaundi.taukitauki

import android.content.Context
import android.preference.PreferenceManager

object KeyFilter {

    fun prefKey(char: String): String = "char_enabled_${char}"

    // The full set of characters that can be toggled in settings.
    // Only these are ever checked — multi-character syllables are never filtered.
    private val filterableChars = setOf("ç", "ü", "ñ", "q", "w", "v", "f", "y", "c", "@", "&", "|", "%", "*", "+")

    fun isEnabled(context: Context, char: String): Boolean =
        if (char !in filterableChars) true
        else PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(prefKey(char), true)

    fun applyToLayout(
        context: Context,
        layout: Map<Pair<Int, Int>, FlickKey>
    ): Map<Pair<Int, Int>, FlickKey> =
        layout.mapValues { (_, key) -> filterKey(context, key) }

    private fun filterKey(context: Context, key: FlickKey): FlickKey {
        // Only null out a slot if its value is exactly a single filterable character.
        fun f(s: String?) = if (s != null && s in filterableChars && !isEnabled(context, s)) null else s
        return FlickKey(
            tap   = f(key.tap),
            up    = f(key.up),
            down  = f(key.down),
            left  = f(key.left),
            right = f(key.right),
            ul    = f(key.ul),
            ur    = f(key.ur),
            dl    = f(key.dl),
            dr    = f(key.dr),
        )
    }
}
