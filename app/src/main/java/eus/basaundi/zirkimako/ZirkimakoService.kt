package eus.basaundi.zirkimako

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import java.util.concurrent.Executors

class ZirkimakoService : InputMethodService() {
    private lateinit var keyboardView: ZirkimakoView
    private lateinit var suggestions: List<TextView>
    
    private var composingWord = StringBuilder()
    private var currentMode = KeyboardMode.LOWER
    private var nextShifted = false
    private var lastInput: String = ""

    private val mutationMap = mapOf(
        "k" to "g", "g" to "k", "z" to "tz", "tz" to "z",
        "t" to "d", "d" to "t", "n" to "l", "l" to "n",
        "p" to "b", "b" to "p", "s" to "ts", "ts" to "s",
        "x" to "tx", "tx" to "x", "r" to "rr", "rr" to "r",
        "m" to "j", "j" to "m", "h" to "",
        "a" to "ha", "e" to "he", "i" to "hi", "o" to "ho", "u" to "hu", "ü" to "hü",
        "(" to ")", ")" to "(", "\"" to "'", "'" to "\"", "{" to "}", "}" to "{",
        "<" to ">", ">" to "<", "[" to "]", "]" to "[", "/" to "\\", "\\" to "/"
    )

    private lateinit var dbHelper: DictionaryDatabaseHelper
    private val uiHandler = Handler(Looper.getMainLooper())
    // Use a single thread executor to prevent memory leaks from firing new threads every keystroke
    private val dbExecutor = Executors.newSingleThreadExecutor() 

    private val basqueLayout = mapOf(
        Pair(0, 0) to FlickKey("(", "[", "{", "<", "\"", ur = "/", dl = "@", dr = "&", ul = "|"),
        Pair(0, 1) to FlickKey("a", "u", "o", "i", "e", ur = "ü", dr = "h", dl = "y", ul = "w"),
        Pair(0, 2) to FlickKey("ka", "ku", "ko", "ki", "ke", ur = "k", dr = "c", dl = "g", ul = "q"),
        Pair(0, 3) to FlickKey("za", "zu", "zo", "zi", "ze", ur = "z", dl = "tz", ul = "ç"),
        Pair(1, 1) to FlickKey("ta", "tu", "to", "ti", "te", ur = "t", dl = "d"),
        Pair(1, 2) to FlickKey("na", "nu", "no", "ni", "ne", ur = "n", dr = "ñ", dl = "l"),
        Pair(1, 3) to FlickKey("ba", "bu", "bo", "bi", "be", ur = "b", dr = "f", dl = "p", ul = "v"),
        Pair(2, 0) to FlickKey("⇧"),
        Pair(2, 1) to FlickKey("ma", "mu", "mo", "mi", "me", ur = "m", dl = "j"),
        Pair(2, 2) to FlickKey("sa", "su", "so", "si", "se", ur = "s", dl = "tz"),
        Pair(2, 3) to FlickKey("ra", "ru", "ro", "ri", "re", ur = "r", dl = "h"),
        Pair(3, 2) to FlickKey("xa", "xu", "xo", "xi", "xe", ur = "x", dl = "tx"),
        Pair(3, 3) to FlickKey(", ", "? ", ". ", "-", "! ", ur = "@", ul = "%", dr = "*", dl = "+")
    )

    private val numLayout = mapOf(
        Pair(0, 1) to FlickKey("1"), Pair(0, 2) to FlickKey("2"), Pair(0, 3) to FlickKey("3"),
        Pair(1, 1) to FlickKey("4"), Pair(1, 2) to FlickKey("5"), Pair(1, 3) to FlickKey("6"),
        Pair(2, 1) to FlickKey("7"), Pair(2, 2) to FlickKey("8"), Pair(2, 3) to FlickKey("9"),
        Pair(3, 2) to FlickKey("0"),
        Pair(3, 3) to FlickKey(".", ",", ":", ";", "/", ul = "(", ur = ")", dl = "[", dr = "]")
    )

    enum class KeyboardMode { LOWER, UPPER, NUM }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictionaryDatabaseHelper(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        dbExecutor.shutdown()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = root.findViewById(R.id.zirkimako_keyboard_view)
        
        // Grouping suggestion text views into a clean list
        suggestions = listOf(
            root.findViewById(R.id.sug1),
            root.findViewById(R.id.sug2),
            root.findViewById(R.id.sug3),
            root.findViewById(R.id.sug4),
            root.findViewById(R.id.sug5)
        )
        
        keyboardView.layoutMap = basqueLayout
        keyboardView.keyActionListener = { r, c, d -> handleAction(r, c, d) }
        keyboardView.backspaceListener = { handleBackspace() }
        keyboardView.longPressListener = { r, c -> 
            if (r == 2 && c == 4) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        
        val suggestionListener = View.OnClickListener { v -> 
            val suggestion = (v as TextView).text.toString()
            if (suggestion.isNotEmpty()) {
                val formatted = when {
                    currentMode == KeyboardMode.UPPER -> suggestion.uppercase()
                    nextShifted -> suggestion.replaceFirstChar { it.uppercase() }
                    else -> suggestion.lowercase()
                }
                commitWord("$formatted ")
            }
        }
        
        suggestions.forEach { it.setOnClickListener(suggestionListener) }
        
        return root
    }

    private fun handleAction(r: Int, c: Int, d: FlickDirection) {
        val ic = currentInputConnection ?: return

        when {
            r == 2 && c == 0 -> { // shift
                if (currentMode != KeyboardMode.NUM) {
                    nextShifted = !nextShifted
                    refreshKeyboardModeUI()
                }
            }
            r == 1 && c == 0 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)); updateCapsMode() }
            r == 1 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); updateCapsMode() }
            r == 2 && c == 4 -> { commitCurrent(); ic.commitText(" ", 1); updateCapsMode() }
            r == 3 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); updateCapsMode() }
            r == 3 && c == 0 -> cycleMode()
            r == 3 && c == 1 -> performMutation()
            else -> {
                val s = getChar(r, c, d) ?: return
                val ic = currentInputConnection ?: return
                ic.beginBatchEdit()
                if (!s.any { it.isLetter() }) {
                    commitCurrent()
                } else {
                    if (composingWord.isNotEmpty() && !composingWord.any { it.isLetter() }) {
                        commitCurrent()
                    }
                }
                composingWord.append(s)
                lastInput = s
                updateUI()
                ic.endBatchEdit()
            }
        }
    }

    private fun commitCurrent() {
        if (composingWord.isNotEmpty()) {
            currentInputConnection?.commitText(composingWord.toString(), 1)
            composingWord.setLength(0)
            lastInput = ""
            nextShifted = false
        }
    }

    // --- Extracted logic to keep mutation DRY ---
    private fun getMutationTarget(input: String): String? {
        if (input.isEmpty()) return null
        
        val searchKey = input.lowercase()
        var targetLower = mutationMap[searchKey]
        var vowel = ""
        
        if (targetLower == null) {
            val vowelRegex = "[aeiouüAEIOUÜ]$".toRegex()
            val match = vowelRegex.find(input)
            if (match != null) {
                vowel = match.value
                val stripped = input.substring(0, input.length - vowel.length).lowercase()
                targetLower = mutationMap[stripped]
            }
        }
        
        if (targetLower == null) return null

        val targetWithCase = when {
            input.all { it.isUpperCase() } -> targetLower.uppercase()
            input[0].isUpperCase() -> targetLower.replaceFirstChar { it.uppercase() }
            else -> targetLower
        }
        
        return targetWithCase + vowel
    }

    private fun performMutation() {
        val mutated = getMutationTarget(lastInput) ?: return
        val idx = composingWord.lastIndexOf(lastInput)
        
        if (idx != -1) {
            composingWord.replace(idx, idx + lastInput.length, mutated)
            lastInput = mutated
            updateUI()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingWord.setLength(0)
        lastInput = ""
        updateUI()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composingWord.setLength(0)
        lastInput = ""
        updateUI()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateCapsMode()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composingWord.isNotEmpty() && candidatesStart == -1 && candidatesEnd == -1) {
            composingWord.setLength(0)
            lastInput = ""
            updateUI()
        }
        updateCapsMode()
    }

    private fun updateCapsMode() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        
        val capsMode = ic.getCursorCapsMode(info.inputType)
        val shouldCapitalize = (capsMode and (TextUtils.CAP_MODE_SENTENCES or TextUtils.CAP_MODE_CHARACTERS or TextUtils.CAP_MODE_WORDS)) != 0

        if (composingWord.isEmpty() && currentMode != KeyboardMode.NUM) {
            if (shouldCapitalize && !nextShifted) {
                nextShifted = true
                refreshKeyboardModeUI()
            } else if (!shouldCapitalize && nextShifted && currentMode != KeyboardMode.UPPER) {
                nextShifted = false
                refreshKeyboardModeUI()
            }
        }
    }

    private fun refreshKeyboardModeUI() {
        if (!::keyboardView.isInitialized) return
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
        
        // PERFORMANCE FIX: Executing DB calls on a reusable thread rather than a new one per keystroke
        dbExecutor.execute {
            val searchPrefix = word.lowercase()
            val preds = dbHelper.getSuggestions(searchPrefix)
            uiHandler.post {
                if (composingWord.toString() == word) {
                    suggestions.forEachIndexed { index, textView -> 
                        textView.text = preds.getOrNull(index) ?: ""
                    }
                }
            }
        }
        
        if (!::keyboardView.isInitialized) return
        
        val mutated = getMutationTarget(lastInput)
        keyboardView.swapLabel = if (mutated != null) {
            getString(R.string.swap_indicator_format, lastInput.lowercase(), mutated)
        } else {
            getString(R.string.label_swap)
        }
        
        keyboardView.invalidate()
    }
    
    private fun commitWord(w: String) { 
        if (w.isNotEmpty()) { 
            currentInputConnection?.commitText(getString(R.string.word_with_space_format, w), 1)
            composingWord.setLength(0)
            lastInput = ""
            nextShifted = false
            updateUI()
            updateCapsMode()
        } 
    }

    private fun handleBackspace() {
        if (composingWord.isNotEmpty()) { 
            composingWord.deleteCharAt(composingWord.length - 1)
            lastInput = "" 
            updateUI() 
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            updateCapsMode()
        }
    }

    private fun cycleMode() {
        currentMode = when(currentMode) { 
            KeyboardMode.LOWER -> KeyboardMode.UPPER
            KeyboardMode.UPPER -> KeyboardMode.NUM
            else -> KeyboardMode.LOWER 
        }
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
