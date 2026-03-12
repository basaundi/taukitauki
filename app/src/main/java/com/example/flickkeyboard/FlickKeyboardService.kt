package com.example.flickkeyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class FlickKeyboardService : InputMethodService() {

    private lateinit var keyboardView: FlickKeyboardView
    private lateinit var btnPasteSuggest: Button
    
    enum class KeyboardMode { LOWER, UPPER, NUM_SYM }
    private var currentMode = KeyboardMode.LOWER

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = view.findViewById(R.id.flick_keyboard_view)
        btnPasteSuggest = view.findViewById(R.id.btn_paste_or_suggest)

        keyboardView.keyActionListener = { row, col, direction ->
            handleKeyAction(row, col, direction)
        }

        btnPasteSuggest.setOnClickListener {
            if (btnPasteSuggest.text == "Paste") {
                pasteText()
            } else {
                // If it's a suggestion, commit the text
                currentInputConnection?.commitText(btnPasteSuggest.text, 1)
            }
        }

        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateSuggestionBar()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateSuggestionBar()
    }

    private fun handleKeyAction(row: Int, col: Int, direction: FlickDirection) {
        // Mode Switcher: Bottom-Left Key (Row 3, Col 0)
        if (row == 3 && col == 0 && direction == FlickDirection.TAP) {
            currentMode = when (currentMode) {
                KeyboardMode.LOWER -> KeyboardMode.UPPER
                KeyboardMode.UPPER -> KeyboardMode.NUM_SYM
                KeyboardMode.NUM_SYM -> KeyboardMode.LOWER
            }
            keyboardView.modeLabel = when (currentMode) {
                KeyboardMode.LOWER -> "abc"
                KeyboardMode.UPPER -> "ABC"
                KeyboardMode.NUM_SYM -> "?123"
            }
            keyboardView.invalidate() // Redraw the label
            return
        }

        // Backspace: Example mapping for Top-Right Key (Row 0, Col 4)
        if (row == 0 && col == 4 && direction == FlickDirection.TAP) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }
        
        // Enter: Example mapping for Bottom-Right Key (Row 3, Col 4)
        if (row == 3 && col == 4 && direction == FlickDirection.TAP) {
            currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            return
        }

        // Space: Example mapping for Bottom Row, Middle Key (Row 3, Col 2)
        if (row == 3 && col == 2 && direction == FlickDirection.TAP) {
             currentInputConnection?.commitText(" ", 1)
             return
        }

        // Retrieve the character based on configuration
        val charToCommit = getCharacterForAction(currentMode, row, col, direction)
        if (charToCommit != null) {
            currentInputConnection?.commitText(charToCommit, 1)
        }
    }

    private fun updateSuggestionBar() {
        val ic = currentInputConnection ?: return
        val textBeforeCursor = ic.getTextBeforeCursor(20, 0)

        if (textBeforeCursor.isNullOrEmpty() || textBeforeCursor.endsWith(" ")) {
            btnPasteSuggest.text = "Paste"
        } else {
            // Note: A true predictive dictionary requires extensive ML or Trie implementations.
            // This is a stub showing how you would insert predicted text into the UI.
            btnPasteSuggest.text = "${textBeforeCursor}ing" 
        }
    }

    private fun pasteText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasteData = item?.text
            if (!pasteData.isNullOrEmpty()) {
                currentInputConnection?.commitText(pasteData, 1)
            }
        }
    }

    // --- YOUR CONFIGURATION MATRIX ---
    // Here is where you map the 180 combinations (3 modes * 20 keys * 9 actions).
    private fun getCharacterForAction(mode: KeyboardMode, row: Int, col: Int, direction: FlickDirection): String? {
        
        // Example configuration for Row 0, Col 0 (Top Left Key)
        if (row == 0 && col == 0) {
            when (mode) {
                KeyboardMode.LOWER -> return when (direction) {
                    FlickDirection.TAP -> "a"
                    FlickDirection.UP -> "b"
                    FlickDirection.RIGHT -> "c"
                    FlickDirection.DOWN -> "d"
                    FlickDirection.LEFT -> "e"
                    // Add diagonals as needed...
                    else -> null
                }
                KeyboardMode.UPPER -> return when (direction) {
                    FlickDirection.TAP -> "A"
                    FlickDirection.UP -> "B"
                    // Add upper combinations...
                    else -> null
                }
                KeyboardMode.NUM_SYM -> return when (direction) {
                    FlickDirection.TAP -> "1"
                    FlickDirection.UP -> "!"
                    // Add symbol combinations...
                    else -> null
                }
            }
        }
        
        // To complete this, you will need to map out `if (row == x && col == y)` 
        // for the remaining keys, or abstract this into a JSON/Map configuration.
        return null 
    }
}
