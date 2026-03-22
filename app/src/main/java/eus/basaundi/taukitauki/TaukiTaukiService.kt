package eus.basaundi.taukitauki

import android.content.ClipboardManager
import android.content.Context
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

class TaukiTaukiService : InputMethodService() {

    // ─── View references ──────────────────────────────────────────────────────

    private lateinit var keyboardView: TaukiTaukiView
    private lateinit var suggestions: List<TextView>
    private lateinit var pasteButton: View

    // ─── State ────────────────────────────────────────────────────────────────

    private var composingWord = StringBuilder()
    private var currentMode = KeyboardMode.LOWER
    private var lastMode = KeyboardMode.LOWER  // tracks mode before NUM so we can restore it
    private var lastInput: String = ""
    private var lastCommittedWord: String = ""

    // Used to detect double-tap on the ⇧ (TITLE) key to engage UPPER mode
    private var lastShiftTapMs: Long = 0L
    private val doubleTapWindowMs = 400L

    // ─── System services ──────────────────────────────────────────────────────

    private lateinit var clipboardManager: ClipboardManager
    private val uiHandler = Handler(Looper.getMainLooper())
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private lateinit var dbHelper: DictionaryDatabaseHelper

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        updatePasteButtonVisibility()
    }

    // ─── Mutation map ─────────────────────────────────────────────────────────

    private val mutationMap = mapOf(
        "k" to "g", "g" to "k", "z" to "tz", "tz" to "z",
        "t" to "d", "d" to "t", "n" to "l", "l" to "n",
        "p" to "b", "b" to "p", "s" to "ts", "ts" to "s",
        "x" to "tx", "tx" to "x", "r" to "rr", "rr" to "r",
        "m" to "j", "j" to "m", "h" to "",
        "a" to "ha", "e" to "he", "i" to "hi", "o" to "ho", "u" to "hu", "ü" to "hü",
        "(" to ")", ")" to "(", "\"" to "'", "'" to "\"", "{" to "}", "}" to "{",
        "<" to ">", ">" to "<", "[" to "]", "]" to "[", "/" to "\\", "\\" to "/",
        "-" to "_", "_" to "-", "?" to "¿", "¿" to "?", "!" to "¡", "¡" to "!",
    )

    // ─── Keyboard layouts ─────────────────────────────────────────────────────

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
        Pair(0, 0) to FlickKey("(", "[", "{", "<", "\"", ur = "/", dl = "@", dr = "&", ul = "|"),
        Pair(0, 1) to FlickKey("1"),
        Pair(0, 2) to FlickKey("2"),
        Pair(0, 3) to FlickKey("3"),
        Pair(1, 1) to FlickKey("4"),
        Pair(1, 2) to FlickKey("5"),
        Pair(1, 3) to FlickKey("6"),
        Pair(2, 1) to FlickKey("7"),
        Pair(2, 2) to FlickKey("8"),
        Pair(2, 3) to FlickKey("9"),
        Pair(3, 2) to FlickKey("0"),
        Pair(3, 3) to FlickKey(".", ",", ":", ";", "/", ul = "(", ur = ")", dl = "[", dr = "]")
    )

    private val qwertyLayout = mapOf(
        // Row 0: q w e r t
        Pair(0, 0) to FlickKey("q"),
        Pair(0, 1) to FlickKey("w"),
        Pair(0, 2) to FlickKey("e", ur = "è", ul = "é", dr = "ë", dl = "ê"),
        Pair(0, 3) to FlickKey("r"),
        Pair(0, 4) to FlickKey("t"),
        // Row 1: y u i o p
        Pair(1, 0) to FlickKey("y"),
        Pair(1, 1) to FlickKey("u", ur = "ú", ul = "ü", dr = "û", dl = "ù"),
        Pair(1, 2) to FlickKey("i", ur = "í", ul = "ï", dl = "ì"),
        Pair(1, 3) to FlickKey("o", ur = "ó", ul = "ö", dr = "ô", dl = "ò"),
        Pair(1, 4) to FlickKey("p"),
        // Row 2: a s d f g  (⇧ at 2,0 is drawn by the view as a special key)
        Pair(2, 0) to FlickKey("⇧"),
        Pair(2, 1) to FlickKey("a", ur = "á", ul = "ä", dr = "â", dl = "à"),
        Pair(2, 2) to FlickKey("s"),
        Pair(2, 3) to FlickKey("d"),
        Pair(2, 4) to FlickKey("f"),
        // Row 3: mode swap h j k  (mode at 3,0; swap at 3,1; then h j k)
        Pair(3, 2) to FlickKey("h"),
        Pair(3, 3) to FlickKey("j", up = "k", down = "l", left = "n", right = "m",
                                ul = "ñ", ur = "ç", dl = "x", dr = "z"),
        Pair(3, 4) to FlickKey(", ", "? ", ". ", "-", "! ", ur = "@", ul = "%", dr = "*", dl = "+")
    )

    private fun layoutForMode(mode: KeyboardMode) = when (mode) {
        KeyboardMode.NUM   -> numLayout
        KeyboardMode.QWERTY -> qwertyLayout
        else               -> basqueLayout
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictionaryDatabaseHelper(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        dbExecutor.shutdown()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = root.findViewById(R.id.taukitauki_keyboard_view)

        suggestions = listOf(
            root.findViewById(R.id.sug1),
            root.findViewById(R.id.sug2),
            root.findViewById(R.id.sug3),
            root.findViewById(R.id.sug4),
            root.findViewById(R.id.sug5)
        )

        pasteButton = root.findViewById(R.id.btn_paste_or_suggest)
        pasteButton.setOnClickListener {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text
                if (!text.isNullOrEmpty()) {
                    commitCurrent()
                    currentInputConnection?.commitText(text, 1)
                }
            }
        }

        keyboardView.keyActionListener  = { r, c, gesture -> handleAction(r, c, gesture) }
        keyboardView.backspaceListener  = { handleBackspace() }
        keyboardView.longPressListener  = { r, c ->
            if (r == 2 && c == 4) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }

        val suggestionClickListener = View.OnClickListener { v ->
            val suggestion = (v as TextView).text.toString()
            if (suggestion.isNotEmpty()) {
                commitWord(applyCase(suggestion))
            }
        }
        suggestions.forEach { it.setOnClickListener(suggestionClickListener) }

        updatePasteButtonVisibility()
        refreshKeyboardModeUI()
        return root
    }

    // ─── Input handling ───────────────────────────────────────────────────────

    private fun handleAction(r: Int, c: Int, gesture: Gesture) {
        val ic = currentInputConnection ?: return

        when {
            // Shift / UPPER toggle
            r == 2 && c == 0 && currentMode != KeyboardMode.NUM -> handleShiftTap()

            // Navigation & control
            r == 1 && c == 0 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));  updateCapsMode() }
            r == 1 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); updateCapsMode() }
            r == 2 && c == 4 -> { commitCurrent(); ic.commitText(" ", 1); updateCapsMode() }
            r == 3 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); updateCapsMode() }
            r == 3 && c == 0 -> cycleMode()
            r == 3 && c == 1 -> performMutation()

            // Character input
            else -> handleCharInput(r, c, gesture)
        }
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastShiftTapMs) < doubleTapWindowMs

        currentMode = when {
            isDoubleTap && currentMode == KeyboardMode.TITLE -> KeyboardMode.UPPER
            currentMode == KeyboardMode.UPPER               -> KeyboardMode.LOWER
            else                                             -> KeyboardMode.TITLE
        }
        lastShiftTapMs = now
        lastMode = currentMode
        refreshKeyboardModeUI()
    }

    private fun handleCharInput(r: Int, c: Int, gesture: Gesture) {
        val ic = currentInputConnection ?: return
        val layout = layoutForMode(currentMode)
        val key = layout[Pair(r, c)] ?: return
        var s = key.getChar(gesture) ?: return

        s = applyCase(s)
        // After applying case for a single typed char, step down from TITLE to LOWER
        if (currentMode == KeyboardMode.TITLE && s.any { it.isLetter() }) {
            currentMode = KeyboardMode.LOWER
            refreshKeyboardModeUI()
        }

        ic.beginBatchEdit()
        // Commit composing buffer if we're switching from letters to non-letters or vice-versa
        if (!s.any { it.isLetter() }) {
            commitCurrent()
        } else if (composingWord.isNotEmpty() && !composingWord.any { it.isLetter() }) {
            commitCurrent()
        }
        composingWord.append(s)
        lastInput = s
        updateUI()
        ic.endBatchEdit()
    }

    // ─── Case helpers ─────────────────────────────────────────────────────────

    private fun applyCase(s: String): String = when (currentMode) {
        KeyboardMode.UPPER  -> s.uppercase()
        KeyboardMode.TITLE  -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else                -> s.lowercase()
    }

    // ─── Commit helpers ───────────────────────────────────────────────────────

    private fun commitCurrent() {
        if (composingWord.isNotEmpty()) {
            currentInputConnection?.commitText(composingWord.toString(), 1)
            composingWord.setLength(0)
            lastInput = ""
        }
    }

    private fun commitWord(word: String) {
        if (word.isNotEmpty()) {
            lastCommittedWord = word.trimEnd()
            currentInputConnection?.commitText("$word ", 1)
            composingWord.setLength(0)
            lastInput = ""
            updateUI()
            updateCapsMode()
        }
    }

    // ─── Mutation ─────────────────────────────────────────────────────────────

    private fun getMutationTarget(input: String): String? {
        if (input.isEmpty()) return null
        val vowelRegex = "[aeiouüAEIOUÜ]$".toRegex()
        val vowelMatch = vowelRegex.find(input)
        val vowel = vowelMatch?.value ?: ""
        val base = if (vowel.isNotEmpty()) input.dropLast(vowel.length) else input
        val targetLower = mutationMap[base.lowercase()] ?: return null

        val cased = when {
            base.all { it.isUpperCase() } -> targetLower.uppercase()
            base.firstOrNull()?.isUpperCase() == true -> targetLower.replaceFirstChar { it.uppercase() }
            else -> targetLower
        }
        return cased + vowel
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

    // ─── Caps-mode auto-detect ────────────────────────────────────────────────

    private fun updateCapsMode() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if (currentMode == KeyboardMode.NUM || currentMode == KeyboardMode.UPPER) return

        val caps = ic.getCursorCapsMode(info.inputType)
        val shouldCap = (caps and (TextUtils.CAP_MODE_SENTENCES or TextUtils.CAP_MODE_CHARACTERS or TextUtils.CAP_MODE_WORDS)) != 0

        if (composingWord.isEmpty()) {
            val newMode = if (shouldCap) KeyboardMode.TITLE else KeyboardMode.LOWER
            if (currentMode != newMode) {
                currentMode = newMode
                refreshKeyboardModeUI()
            }
        }
    }

    // ─── Suggestions ──────────────────────────────────────────────────────────

    private fun updateUI() {
        val ic = currentInputConnection ?: return
        val word = composingWord.toString()
        ic.setComposingText(word, 1)

        dbExecutor.execute {
            val preds: List<String> = when {
                word.isNotEmpty() -> dbHelper.getSuggestions(word.lowercase())
                lastCommittedWord.isNotEmpty() -> dbHelper.getBigramSuggestions(lastCommittedWord)
                else -> emptyList()
            }
            uiHandler.post {
                if (composingWord.toString() == word) {
                    suggestions.forEachIndexed { i, tv -> tv.text = preds.getOrNull(i) ?: "" }
                }
            }
        }

        if (!::keyboardView.isInitialized) return
        val mutated = getMutationTarget(lastInput)
        keyboardView.swapLabel = if (mutated != null)
            getString(R.string.swap_indicator_format, lastInput.lowercase(), mutated)
        else
            getString(R.string.label_swap)
        keyboardView.invalidate()
    }

    // ─── UI refresh ───────────────────────────────────────────────────────────

    private fun refreshKeyboardModeUI() {
        if (!::keyboardView.isInitialized) return
        keyboardView.currentMode  = currentMode
        keyboardView.layoutMap    = layoutForMode(currentMode)
        keyboardView.modeLabel    = when (currentMode) {
            KeyboardMode.LOWER  -> getString(R.string.mode_lower)
            KeyboardMode.TITLE  -> getString(R.string.mode_title)
            KeyboardMode.UPPER  -> getString(R.string.mode_upper)
            KeyboardMode.NUM    -> getString(R.string.mode_number)
            KeyboardMode.QWERTY -> getString(R.string.mode_qwerty)
        }
        keyboardView.invalidate()
    }

    private fun updatePasteButtonVisibility() {
        if (!::pasteButton.isInitialized) return
        pasteButton.visibility = if (clipboardManager.hasPrimaryClip()) View.VISIBLE else View.GONE
    }

    // ─── Mode cycling ─────────────────────────────────────────────────────────

    private fun cycleMode() {
        currentMode = when (currentMode) {
            KeyboardMode.LOWER, KeyboardMode.TITLE, KeyboardMode.UPPER -> {
                lastMode = currentMode
                KeyboardMode.NUM
            }
            KeyboardMode.NUM    -> KeyboardMode.QWERTY
            KeyboardMode.QWERTY -> lastMode.takeIf { it != KeyboardMode.NUM } ?: KeyboardMode.LOWER
        }
        refreshKeyboardModeUI()
    }

    // ─── InputMethodService callbacks ────────────────────────────────────────

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingWord.setLength(0)
        lastInput = ""
        lastCommittedWord = ""
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
        updatePasteButtonVisibility()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composingWord.isNotEmpty() && candidatesStart == -1 && candidatesEnd == -1) {
            composingWord.setLength(0)
            lastInput = ""
            updateUI()
        }
        updateCapsMode()
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
}
