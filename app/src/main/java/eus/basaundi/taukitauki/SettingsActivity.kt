package eus.basaundi.taukitauki

import android.app.Activity
import android.os.Bundle

/**
 * Minimal settings activity required by the Android IME framework.
 * Some versions of Android will not show the keyboard in the input method
 * picker unless a settingsActivity is declared in method.xml.
 * This activity finishes immediately — there are no settings to configure.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
