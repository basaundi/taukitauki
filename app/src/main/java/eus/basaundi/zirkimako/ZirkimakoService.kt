package eus.basaundi.zirkimako

import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.text.TextUtils
import android.view.inputmethod.EditorInfo


class ZirkimakoService : InputMethodService() {

    private lateinit var keyboardView: ZirkimakoView
    private lateinit var sug1: TextView
    private lateinit var sug2: TextView
    private lateinit var sug3: TextView
    private lateinit var sug4: TextView
    private lateinit var sug5: TextView
    private var composingWord = StringBuilder()
    private var currentMode = KeyboardMode.LOWER
    private var nextShifted = false

    private val mutationMap = mapOf(
        "k" to "g", "g" to "k", "z" to "tz", "tz" to "z",
        "t" to "d", "d" to "t", "n" to "l", "l" to "n",
        "p" to "b", "b" to "p", "s" to "ts", "ts" to "s",
        "x" to "tx", "tx" to "x", "r" to "rr", "rr" to "r",
        "m" to "j", "j" to "m",
    )

    private lateinit var dbHelper: DictionaryDatabaseHelper
    private val uiHandler = Handler(Looper.getMainLooper())

    private val basqueLayout = mapOf(
        Pair(0,0) to FlickKey("(","[","{","<","\"",
                              ur="/", dl="@", dr="&", ul="|"),
        Pair(0,1) to FlickKey("a","u","o","i","e",
                              ur="ü", dr="h", dl="y", ul="w"),
        Pair(0,2) to FlickKey("ka","ku","ko","ki","ke",
                              ur="k", dr="c", dl="g", ul="q"),
        Pair(0,3) to FlickKey("za","zu","zo","zi","ze",
                              ur="z", dl="tz", ul="ç"),

        Pair(1,1) to FlickKey("ta","tu","to","ti","te",
                              ur="t", dl="d"),
        Pair(1,2) to FlickKey("na","nu","no","ni","ne",
                              ur="n", dr="ñ", dl="l"),
        Pair(1,3) to FlickKey("ba","bu","bo","bi","be",
                              ur="b", dr="f", dl="p", ul="v"),

        Pair(2,0) to FlickKey("⇧"),

        Pair(2,1) to FlickKey("ma","mu","mo","mi","me",
                              ur="m", dl="j"),
        Pair(2,2) to FlickKey("sa","su","so","si","se",
                               ur="s", dl="tz"),
        Pair(2,3) to FlickKey("ra","ru","ro","ri","re",
                              ur="r", dl="h"),

        Pair(3,2) to FlickKey("xa","xu","xo","xi","xe",
                              ur="x", dl="tx"),
        Pair(3,3) to FlickKey(", ","? ",". ","-","! ",
                              ur="@", ul="%", dr="*", dl="+")
    )

    private val numLayout = mapOf(
        Pair(0,1) to FlickKey("1"),
        Pair(0,2) to FlickKey("2"),
        Pair(0,3) to FlickKey("3"),
        Pair(1,1) to FlickKey("4"),
        Pair(1,2) to FlickKey("5"),
        Pair(1,3) to FlickKey("6"),
        Pair(2,1) to FlickKey("7"),
        Pair(2,2) to FlickKey("8"),
        Pair(2,3) to FlickKey("9"),
        Pair(3,2) to FlickKey("0"),
        Pair(3,3) to FlickKey(".", ",", ":", ";", "/",
                              ul="(", ur=")", dl="[", dr="]") //, up="<", down=">", left="{", right="}")
    )

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = root.findViewById(R.id.zirkimako_keyboard_view)
        sug1 = root.findViewById(R.id.sug1)
        sug2 = root.findViewById(R.id.sug2)
        sug3 = root.findViewById(R.id.sug3)
        sug4 = root.findViewById(R.id.sug4)
        sug5 = root.findViewById(R.id.sug5)
        
        keyboardView.layoutMap = basqueLayout
        keyboardView.keyActionListener = { r, c, d -> handleAction(r, c, d) }
        keyboardView.backspaceListener = { handleBackspace() }
        keyboardView.longPressListener = { r, c -> if (r==2 && c==4) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        
        val sL = View.OnClickListener { v -> 
            val suggestion = (v as TextView).text.toString()
            if (suggestion.isNotEmpty()) {
                val formatted = when {
                    currentMode == KeyboardMode.UPPER -> suggestion.uppercase()
                    nextShifted -> suggestion.replaceFirstChar { it.uppercase() }
                    else -> suggestion.lowercase()
                }
                commitWord(formatted)
            }
        }
        sug1.setOnClickListener(sL)
        sug2.setOnClickListener(sL)
        sug3.setOnClickListener(sL)
        sug4.setOnClickListener(sL)
        sug5.setOnClickListener(sL)
        
        return root
    }

    private fun handleAction(r: Int, c: Int, d: FlickDirection) {
        val ic = currentInputConnection ?: return
        when {
            // shift (2,0)
            r == 2 && c == 0 -> {
                if (currentMode != KeyboardMode.NUM) {
                    nextShifted = !nextShifted
                    refreshKeyboardModeUI()
                }
            }
            // <- arrow
            r == 1 && c == 0 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
            // -> arrow
            r == 1 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            // _ space
            r == 2 && c == 4 -> { commitCurrent(); ic.commitText(" ", 1) }
            // enter
            r == 3 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }

            // lowercase - uppercase - numpad
            r == 3 && c == 0 -> cycleMode()
            // mutate consonant
            r == 3 && c == 1 -> performMutation()
            else -> {
                val s = getChar(r, c, d) ?: return
                if (s.matches(Regex("[.,?!;:/()\\[\\]{}<>@%*+-]\\s*"))) {
                    commitCurrent()
                    ic.commitText(s, 1)
                } else {
                    composingWord.append(s)
                    updateUI()
                }
            }
        }
    }

    private fun performMutation() {
        val word = composingWord.toString()
        val lastCWithCase = getLC(word)
        if (lastCWithCase.isEmpty()) return

        val lastCLower = lastCWithCase.lowercase()
        val targetLower = mutationMap[lastCLower] ?: return

        // Preserve case:
        // 1. If original was all uppercase, make target all uppercase
        // 2. If original was titlecase (first char upper), make target titlecase
        // 3. Otherwise, use lowercase target
        val targetWithCase = when {
            lastCWithCase.all { it.isUpperCase() } -> targetLower.uppercase()
            lastCWithCase[0].isUpperCase() -> targetLower.replaceFirstChar { it.uppercase() }
            else -> targetLower
        }

        val idx = word.lastIndexOf(lastCWithCase)
        if (idx != -1) {
            composingWord.replace(idx, idx + lastCWithCase.length, targetWithCase)
            updateUI()
        }
    }

    private fun getLC(w: String): String {
        val motz = w.replace("[aeiouAEIOU]+$".toRegex(), "")
        if (motz.length >= 2) {
            val l2 = motz.takeLast(2)
            if (l2.lowercase() in listOf("tz", "tx", "ts", "rr")) return l2
        }
        val l1 = motz.takeLast(1)
        return if (l1.lowercase() in mutationMap) l1 else ""
    }

    enum class KeyboardMode { LOWER, UPPER, NUM }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictionaryDatabaseHelper(this)
    }

    // Auto-capitalization trigger when input view starts
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateCapsMode()
    }

    // Auto-capitalization trigger when cursor moves or text changes externally
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateCapsMode()
    }

    private fun updateCapsMode() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        
        // Check if Android's input system thinks we should capitalize based on the cursor position
        val capsMode = ic.getCursorCapsMode(info.inputType)
        val shouldCapitalize = (capsMode and (TextUtils.CAP_MODE_SENTENCES or TextUtils.CAP_MODE_CHARACTERS or TextUtils.CAP_MODE_WORDS)) != 0

        if (composingWord.isEmpty() && currentMode != KeyboardMode.NUM) {
            if (shouldCapitalize && !nextShifted) {
                nextShifted = true
                refreshKeyboardModeUI()
            } else if (!shouldCapitalize && nextShifted && currentMode != KeyboardMode.UPPER) {
                // If the system says we shouldn't capitalize, and we are shifted but NOT in full UPPER mode,
                // then we should turn off shift.
                nextShifted = false
                refreshKeyboardModeUI()
            }
        }
    }

    private fun refreshKeyboardModeUI() {
        keyboardView.isUppercase = (currentMode == KeyboardMode.UPPER)
        keyboardView.isShifted = nextShifted
        keyboardView.layoutMap = if (currentMode == KeyboardMode.NUM) numLayout else basqueLayout
        keyboardView.modeLabel = when(currentMode) {
            KeyboardMode.LOWER -> getString(R.string.mode_lower)
            KeyboardMode.UPPER -> getString(R.string.mode_upper)
            else -> getString(R.string.mode_number)
        }
        keyboardView.invalidate()
    }

    private fun updateUI() {
        val ic = currentInputConnection ?: return
        val word = composingWord.toString()
        ic.setComposingText(word, 1)
        
        Thread {
            val searchPrefix = word.lowercase()
            val preds = dbHelper.getSuggestions(searchPrefix)
            uiHandler.post {
                if(composingWord.toString() == word){
                    sug1.text = preds.getOrNull(0) ?: ""
                    sug2.text = preds.getOrNull(1) ?: ""
                    sug3.text = preds.getOrNull(2) ?: ""
                    sug4.text = preds.getOrNull(3) ?: ""
                    sug5.text = preds.getOrNull(4) ?: ""
            }
        }
        }.start()
        
        val lastC = getLC(composingWord.toString())
        keyboardView.swapLabel = if (lastC.isNotEmpty()) {
            val lastCLower = lastC.lowercase()
            getString(R.string.swap_indicator_format, lastCLower, mutationMap[lastCLower])
        } else {
            getString(R.string.label_swap)
        }
        keyboardView.invalidate()
    }

    private fun commitCurrent() { 
        if (composingWord.isNotEmpty()) { 
            currentInputConnection?.commitText(composingWord.toString(), 1)
            composingWord.clear()
            nextShifted = false
            updateUI()
            updateCapsMode() // Check for caps after committing (e.g., after a period)
        } 
    }
    
    private fun commitWord(w: String) { 
        if (w.isNotEmpty()) { 
            currentInputConnection?.commitText(getString(R.string.word_with_space_format, w), 1)
            composingWord.clear()
            nextShifted = false
            updateUI()
            updateCapsMode() // Check for caps after committing a word
        } 
    }

    private fun handleBackspace() {
        if (composingWord.isNotEmpty()) { 
            composingWord.deleteCharAt(composingWord.length - 1)
            updateUI() 
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            updateCapsMode() // Update caps if user deletes a period/space
        }
        if (nextShifted && composingWord.isEmpty()) {
             // If we backspace until the word is empty, we might need to re-evaluate shift state
             // based on what's before the cursor. updateCapsMode() already called above handles this.
        }
    }

    private fun cycleMode() {
        currentMode = when(currentMode) { KeyboardMode.LOWER -> KeyboardMode.UPPER; KeyboardMode.UPPER -> KeyboardMode.NUM; else -> KeyboardMode.LOWER }
        nextShifted = false
        refreshKeyboardModeUI()
    }

    private fun getChar(r: Int, c: Int, d: FlickDirection): String? {
        val layout = if (currentMode == KeyboardMode.NUM) numLayout else basqueLayout
        val k = layout[Pair(r,c)] ?: return null
        val charStr = when(d) { 
            FlickDirection.TAP -> k.tap
            FlickDirection.UP -> k.up
            FlickDirection.RIGHT -> k.right
            FlickDirection.LEFT -> k.left
            FlickDirection.DOWN -> k.down
            FlickDirection.UP_RIGHT -> k.ur
            FlickDirection.DOWN_RIGHT -> k.dr
            FlickDirection.DOWN_LEFT -> k.dl
            FlickDirection.UP_LEFT -> k.ul
        } ?: return null
        
        return when {
            currentMode == KeyboardMode.UPPER -> charStr.uppercase()
            nextShifted -> {
                val res = charStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                nextShifted = false
                refreshKeyboardModeUI()
                res
            }
            else -> charStr
        }
    }
}
