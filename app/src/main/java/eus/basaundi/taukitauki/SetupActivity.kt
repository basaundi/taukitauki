package eus.basaundi.taukitauki

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var step1Status: TextView
    private lateinit var step2Status: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        step1Status = findViewById(R.id.step1_status)
        step2Status = findViewById(R.id.step2_status)
        actionButton = findViewById(R.id.btn_action)
        actionButton.setOnClickListener { onActionClicked() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isSelected(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.currentInputMethodSubtype != null &&
               imm.enabledInputMethodList.firstOrNull { it.packageName == packageName } != null &&
               Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                   ?.startsWith(packageName) == true
    }

    private fun refresh() {
        val enabled = isEnabled()
        val selected = enabled && isSelected()

        step1Status.text = if (enabled) "✓" else "○"
        step2Status.text = if (selected) "✓" else "○"

        actionButton.text = when {
            !enabled  -> getString(R.string.setup_btn_enable)
            !selected -> getString(R.string.setup_btn_switch)
            else      -> getString(R.string.setup_btn_done)
        }
        actionButton.isEnabled = !selected
    }

    private fun onActionClicked() {
        if (!isEnabled()) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
