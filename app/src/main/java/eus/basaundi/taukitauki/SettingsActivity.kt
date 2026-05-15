package eus.basaundi.taukitauki

import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.SwitchPreference
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen

/**
 * Settings screen for TaukiTauki.
 * Lets the user disable individual characters from the Basque layout.
 * Preferences are stored under keys like "char_enabled_ç", "char_enabled_q", etc.
 * The service reads these on every key press via KeyFilter.
 */
class SettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, TaukiTaukiPrefsFragment())
            .commit()
    }

    class TaukiTaukiPrefsFragment : PreferenceFragment() {

        // Characters that can be individually toggled, grouped by category.
        // Each entry: (key-suffix, display label, default enabled)
        private val charGroups get() = listOf(
            getString(R.string.settings_category_uncommon) to listOf(
                Triple("ç",  "ç  (c cedilla)",      true),
                Triple("ü",  "ü  (u umlaut)",        true),
                Triple("ñ",  "ñ  (n tilde)",         true),
            ),
            getString(R.string.settings_category_latin) to listOf(
                Triple("q",  "q",   true),
                Triple("w",  "w",   true),
                Triple("v",  "v",   true),
                Triple("f",  "f",   true),
                Triple("y",  "y",   true),
                Triple("c",  "c",   true),
            ),
            getString(R.string.settings_category_punctuation) to listOf(
                Triple("@",  "@  (at)",              true),
                Triple("&",  "&  (ampersand)",        true),
                Triple("|",  "|  (pipe)",             true),
                Triple("%",  "%  (percent)",          true),
                Triple("*",  "*  (asterisk)",         true),
                Triple("+",  "+  (plus)",             true),
            )
        )

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceScreen = buildPreferenceScreen()
        }

        private fun buildPreferenceScreen(): PreferenceScreen {
            val screen = preferenceManager.createPreferenceScreen(activity)
            for ((groupTitle, entries) in charGroups) {
                val cat = PreferenceCategory(activity).apply { title = groupTitle }
                screen.addPreference(cat)
                for ((key, label, default) in entries) {
                    val pref = SwitchPreference(activity).apply {
                        this.key       = KeyFilter.prefKey(key)
                        this.title     = label
                        this.isChecked = PreferenceManager
                            .getDefaultSharedPreferences(activity)
                            .getBoolean(KeyFilter.prefKey(key), default)
                        setDefaultValue(default)
                    }
                    cat.addPreference(pref)
                }
            }
            return screen
        }
    }
}
