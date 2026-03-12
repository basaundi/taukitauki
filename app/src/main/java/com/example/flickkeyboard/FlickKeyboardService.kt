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
    private lateinit var sug1: TextView
    private lateinit var sug2: TextView
    private lateinit var sug3: TextView
    private var composingWord = StringBuilder()
    private var currentMode = KeyboardMode.LOWER

    private val mutationMap = mapOf(
        "k" to "g", "g" to "k", "z" to "tz", "tz" to "z",
        "t" to "d", "d" to "t", "n" to "ñ", "ñ" to "n",
        "p" to "b", "b" to "p", "s" to "ts", "ts" to "s",
        "x" to "tx", "tx" to "x", "l" to "r", "r" to "l"
    )

    private val basqueDict = mapOf("kaixo" to 100, "egun" to 90, "on" to 85, "eta" to 80, "ba" to 70, "ez" to 60, "baina" to 50)

    private val basqueLayout = mapOf(
        Pair(0,0) to FlickKey("sa","su","so","si","se"),
        Pair(0,1) to FlickKey("a","u","o","i","e"),
        Pair(0,2) to FlickKey("ka","ku","ko","ki","ke"),
        Pair(0,3) to FlickKey("za","zu","zo","zi","ze"),
        Pair(1,1) to FlickKey("ta","tu","to","ti","te"),
        Pair(1,2) to FlickKey("na","nu","no","ni","ne"),
        Pair(1,3) to FlickKey("pa","pu","po","pi","pe"),
        Pair(2,0) to FlickKey("xa","xu","xo","xi","xe"),
        Pair(2,1) to FlickKey("ma","mu","mo","mi","me"),
        Pair(2,2) to FlickKey("ja","ju","jo","ji","je"),
        Pair(2,3) to FlickKey("la","lu","lo","li","le"),
        Pair(3,2) to FlickKey("ha","hu","ho","hi","he"),
        Pair(3,3) to FlickKey(", ","? ",". ","; ","! ")
    )

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = root.findViewById(R.id.flick_keyboard_view)
        sug1 = root.findViewById(R.id.sug1); sug2 = root.findViewById(R.id.sug2); sug3 = root.findViewById(R.id.sug3)
        
        keyboardView.layoutMap = basqueLayout
        keyboardView.keyActionListener = { r, c, d -> handleAction(r, c, d) }
        keyboardView.backspaceListener = { handleBackspace() }
        keyboardView.longPressListener = { r, c -> if (r==2 && c==4) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        
        val sL = View.OnClickListener { v -> commitWord((v as TextView).text.toString()) }
        sug1.setOnClickListener(sL); sug2.setOnClickListener(sL); sug3.setOnClickListener(sL)
        
        return root
    }

    private fun handleAction(r: Int, c: Int, d: FlickDirection) {
        val ic = currentInputConnection ?: return
        when {
            r == 1 && c == 0 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
            r == 1 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            r == 2 && c == 4 -> { commitCurrent(); ic.commitText(" ", 1) }
            r == 3 && c == 0 -> cycleMode()
            r == 3 && c == 1 -> performMutation()
            r == 3 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
            else -> {
                val s = getChar(r, c, d) ?: return
                if (s.matches(Regex("[.,?!;]\\s*"))) { commitCurrent(); ic.commitText(s, 1) } 
                else { composingWord.append(s); updateUI() }
            }
        }
    }

    private fun performMutation() {
        val word = composingWord.toString()
        val lastC = getLC(word)
        val target = mutationMap[lastC] ?: return
        val idx = word.lastIndexOf(lastC)
        if (idx != -1) {
            composingWord.replace(idx, idx + lastC.length, target)
            updateUI()
        }
    }

    private fun getLC(w: String): String {
        val motz = w.replace("[aeiouAEIOU]+$".toRegex(), "")
        if (motz.length >= 2) {
            val l2 = motz.takeLast(2).lowercase()
            if (l2 in listOf("tz", "tx", "ts")) return l2
        }
        val l1 = motz.takeLast(1).lowercase()
        return if (l1 in mutationMap) l1 else ""
    }

    private fun updateUI() {
        val ic = currentInputConnection ?: return
        ic.setComposingText(composingWord.toString(), 1)
        val preds = basqueDict.filterKeys { it.startsWith(composingWord.toString().lowercase()) }.toList().sortedByDescending { it.second }.map { it.first }
        sug1.text = preds.getOrNull(0) ?: ""; sug2.text = preds.getOrNull(1) ?: ""; sug3.text = preds.getOrNull(2) ?: ""
        
        val lastC = getLC(composingWord.toString())
        keyboardView.swapLabel = if (lastC.isNotEmpty()) "$lastC→${mutationMap[lastC]}" else "⟳"
        keyboardView.invalidate()
    }

    private fun handleBackspace() {
        if (composingWord.isNotEmpty()) { composingWord.deleteCharAt(composingWord.length - 1); updateUI() }
        else currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
    }

    private fun commitCurrent() { if (composingWord.isNotEmpty()) { currentInputConnection?.commitText(composingWord.toString(), 1); composingWord.clear(); updateUI() } }
    
    private fun commitWord(w: String) { if (w.isNotEmpty()) { currentInputConnection?.commitText("$w ", 1); composingWord.clear(); updateUI() } }

    private fun cycleMode() {
        currentMode = when(currentMode) { KeyboardMode.LOWER -> KeyboardMode.UPPER; KeyboardMode.UPPER -> KeyboardMode.NUM; else -> KeyboardMode.LOWER }
        keyboardView.isUppercase = (currentMode == KeyboardMode.UPPER)
        keyboardView.modeLabel = when(currentMode) { KeyboardMode.LOWER -> "abc"; KeyboardMode.UPPER -> "ABC"; else -> "?123" }
        keyboardView.invalidate()
    }

    private fun getChar(r: Int, c: Int, d: FlickDirection): String? {
        val k = basqueLayout[Pair(r,c)] ?: return null
        return when(d) { FlickDirection.TAP->k.tap; FlickDirection.UP->k.up; FlickDirection.RIGHT->k.right; FlickDirection.DOWN->k.down; else->k.left }
    }

    enum class KeyboardMode { LOWER, UPPER, NUM }
}
