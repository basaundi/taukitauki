package com.example.flickkeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

class FlickKeyboardService : InputMethodService() {

    private lateinit var keyboardView: FlickKeyboardView
    private lateinit var candidatesBar: LinearLayout
    private lateinit var sug1: TextView
    private lateinit var sug2: TextView
    private lateinit var sug3: TextView
    
    private var currentMode = KeyboardMode.LOWER
    private var composingWord = StringBuilder()

    // Mock Basque Frequency Dictionary (Word to Frequency Score)
    private val basqueDictionary = mapOf(
        "kaixo" to 100, "eta" to 90, "bai" to 85, "ez" to 80,
        "baina" to 75, "katu" to 60, "zuri" to 50, "egun" to 45,
        "on" to 40, "eskerrik" to 30, "asko" to 20, "zure" to 15,
        "zuzena" to 10, "katua" to 9, "kalea" to 8
    )

    private val textMap = mapOf(
        Pair(0, 1) to FlickKey(tap="a", up="u", right="e", down="o", left="i"),
        Pair(0, 2) to FlickKey(tap="ka", up="ku", right="ke", down="ko", left="ki"),
        Pair(0, 3) to FlickKey(tap="za", up="zu", right="ze", down="zo", left="zi"),
        
        Pair(1, 1) to FlickKey(tap="ta", up="tu", right="te", down="to", left="ti"),
        Pair(1, 2) to FlickKey(tap="na", up="nu", right="ne", down="no", left="ni"),
        Pair(1, 3) to FlickKey(tap="pa", up="pu", right="pe", down="po", left="pi"),
        
        Pair(2, 1) to FlickKey(tap="ma", up="mu", right="me", down="mo", left="mi"),
        Pair(2, 3) to FlickKey(tap="la", up="lu", right="le", down="lo", left="li"),
        
        Pair(3, 1) to FlickKey(tap="📋"),
        Pair(3, 2) to FlickKey(tap="ha", up="hu", right="he", down="ho", left="hi"),
        Pair(3, 3) to FlickKey(tap=",", up="?", right="!", down=";", left=".")
    )

    private val numMap = mapOf(
        Pair(0, 0) to FlickKey(tap="+", up="-", right="*", down="/"),
        Pair(0, 1) to FlickKey(tap="7"),
        Pair(0, 2) to FlickKey(tap="8"),
        Pair(0, 3) to FlickKey(tap="9"),
        // (0,4) Reserved: Backspace
        
        // (1,0) Reserved: Left
        Pair(1, 1) to FlickKey(tap="4"),
        Pair(1, 2) to FlickKey(tap="5"),
        Pair(1, 3) to FlickKey(tap="6"),
        // (1,4) Reserved: Right

        Pair(2, 0) to FlickKey(tap="="),
        Pair(2, 1) to FlickKey(tap="1"),
        Pair(2, 2) to FlickKey(tap="2"),
        Pair(2, 3) to FlickKey(tap="3"),
        // (2,4) Reserved: Space

        // (3,0) Reserved: Mode
        Pair(3, 1) to FlickKey(tap="📋"), // Keep paste here too
        Pair(3, 2) to FlickKey(tap="0"),
        Pair(3, 3) to FlickKey(tap=".")
        // (3,4) Reserved: Enter
    )

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = view.findViewById(R.id.flick_keyboard_view)
        candidatesBar = view.findViewById(R.id.candidates_bar)
        sug1 = view.findViewById(R.id.sug1)
        sug2 = view.findViewById(R.id.sug2)
        sug3 = view.findViewById(R.id.sug3)
        
        keyboardView.layoutMap = textMap
        
        // Suggestion Click Listeners
        val sugListener = View.OnClickListener { v ->
            val text = (v as TextView).text.toString()
            commitPredictedWord(text)
        }
        sug1.setOnClickListener(sugListener); sug2.setOnClickListener(sugListener); sug3.setOnClickListener(sugListener)

        keyboardView.keyActionListener = { row, col, dir -> handleAction(row, col, dir) }
        
        keyboardView.backspaceListener = {
            if (composingWord.isNotEmpty()) {
                composingWord.deleteCharAt(composingWord.length - 1)
                updateComposingText()
            } else {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }
        }

        keyboardView.longPressListener = { row, col ->
            if (row == 2 && col == 4) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        return view
    }

    private fun handleAction(row: Int, col: Int, dir: FlickDirection) {
        val ic = currentInputConnection ?: return
        when {
            row == 0 && col == 4 -> return // Backspace handled above
            row == 1 && col == 0 -> { commitCurrentWord(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
            row == 1 && col == 4 -> { commitCurrentWord(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            row == 2 && col == 4 -> { commitCurrentWord(); ic.commitText(" ", 1) } // Space commits the word
            row == 3 && col == 0 && dir == FlickDirection.TAP -> cycleMode()
            row == 3 && col == 4 -> { commitCurrentWord(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
            else -> {
                val char = getMappedChar(row, col, dir) ?: return
                if (char == "📋") { /* (keep your paste logic here) */ return }
                
                // If punctuation, commit word first, then punctuate
                if (char.matches(Regex("[.,?!;]"))) {
                    commitCurrentWord()
                    ic.commitText(char, 1)
                } else {
                    // Add syllable to composing buffer
                    composingWord.append(char)
                    updateComposingText()
                }
            }
        }
    }

    private fun updateComposingText() {
        val ic = currentInputConnection ?: return
        if (composingWord.isEmpty()) {
            ic.finishComposingText()
	    sug1.text = ""
	    sug2.text = ""
	    sug3.text = ""
            return
        }

        // 1. Send composing text to the app (adds the underline)
        ic.setComposingText(composingWord.toString(), 1)

        // 2. Predict Basque Words
        val predictions = basqueDictionary.filterKeys { it.startsWith(composingWord.toString().lowercase()) }
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }

        // 3. Update the Bar
        candidatesBar.visibility = View.VISIBLE
        sug1.text = predictions.getOrNull(0) ?: ""
        sug2.text = predictions.getOrNull(1) ?: ""
        sug3.text = predictions.getOrNull(2) ?: ""
    }

    private fun commitCurrentWord() {
        if (composingWord.isNotEmpty()) {
            currentInputConnection?.commitText(composingWord.toString(), 1)
            composingWord.clear()
            candidatesBar.visibility = View.GONE
        }
    }

    private fun commitPredictedWord(word: String) {
        if (word.isEmpty()) return
        currentInputConnection?.commitText("$word ", 1) // Commit with a trailing space
        composingWord.clear()
        candidatesBar.visibility = View.GONE
    }

    private fun getMappedChar(row: Int, col: Int, dir: FlickDirection): String? {
        val key = keyboardView.layoutMap[Pair(row, col)] ?: return null
        val output = when (dir) {
            FlickDirection.TAP -> key.tap
            FlickDirection.UP -> key.up
            FlickDirection.DOWN -> key.down
            FlickDirection.LEFT -> key.left
            FlickDirection.RIGHT -> key.right
            FlickDirection.UP_LEFT -> key.ul
            FlickDirection.UP_RIGHT -> key.ur
            FlickDirection.DOWN_LEFT -> key.dl
            FlickDirection.DOWN_RIGHT -> key.dr
        }
        return if (currentMode == KeyboardMode.UPPER) output?.uppercase() else output
    }

    private fun cycleMode() {
        currentMode = when (currentMode) {
            KeyboardMode.LOWER -> KeyboardMode.UPPER
            KeyboardMode.UPPER -> KeyboardMode.NUM_SYM
            else -> KeyboardMode.LOWER
        }
        
        // Push the new state to the View
        keyboardView.modeLabel = when (currentMode) {
            KeyboardMode.LOWER -> "abc"
            KeyboardMode.UPPER -> "ABC"
            else -> "?123"
        }
        keyboardView.isUppercase = (currentMode == KeyboardMode.UPPER)
        keyboardView.layoutMap = if (currentMode == KeyboardMode.NUM_SYM) numMap else textMap
        
        keyboardView.invalidate()
    }

    enum class KeyboardMode { LOWER, UPPER, NUM_SYM }
}
