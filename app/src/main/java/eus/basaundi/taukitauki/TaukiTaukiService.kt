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
    private var lastMode = KeyboardMode.LOWER
    private var qwertyActive = false   // true when QWERTY layout is shown
    private var lastInput: String = ""
    private var lastInputMode: KeyboardMode = KeyboardMode.LOWER
    private var lastCommittedWord: String = ""
    @Volatile private var suggestionSeq: Int = 0  // incremented on every updateUI call

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
        "ha" to "a", "he" to "e", "hi" to "i", "ho" to "o", "hu" to "u", "hü" to "ü",
        "(" to ")", ")" to "(", "\"" to "'", "'" to "\"",
        "{" to "}", "}" to "{",
        "<" to ">", ">" to "<", "[" to "]", "]" to "[",
        "/" to "\\", "\\" to "/",
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

    // QWERTY layout as a FlickKey grid.
    // Rows 0-2 only (row 3 bottom bar is handled entirely by handleQwertyAction).
    // tap = the letter; up = digit (row 0) or symbol; other directions = accents / punctuation.
    // null entries = shift (2,0) and backspace (2,8) — handled separately.
    private val qwertyLayout: List<List<FlickKey?>> = listOf(
        // Row 0: q  w  e  r  t  y  u  i  o  p
        listOf(
            FlickKey("q", up="1", down="!", left="~",  right="@",  ul="`",  ur="\u00a1", dl="\\", dr="#"),
            FlickKey("w", up="2", down="?", left="<",  right=">",  ul="\u00ab", ur="\u00bb", dl="{",  dr="}"),
            FlickKey("e", up="3", down="\u20ac", left="\u00e8", right="\u00e9", ul="\u00ea", ur="\u00eb", dl="\u011b", dr="\u1ebd"),
            FlickKey("r", up="4", down="\u00ae", left="(",  right=")",  ul="[",  ur="]", dl="{",  dr="}"),
            FlickKey("t", up="5", down="+", left="\u2013", right="\u2014", ul="\u2022", ur="\u2020", dl="\u2021", dr="\u00d7"),
            FlickKey("y", up="6", down="\u00a5", left="\u00fd", right="\u00ff", ul="^",  ur="&", dl="|",  dr="\u00f7"),
            FlickKey("u", up="7", down="_", left="\u00f9", right="\u00fa", ul="\u00fb", ur="\u00fc", dl="\u016b", dr="\u0169"),
            FlickKey("i", up="8", down="=", left="\u00ec", right="\u00ed", ul="\u00ee", ur="\u00ef", dl="\u0129", dr="\u012b"),
            FlickKey("o", up="9", down="\u00b0", left="\u00f2", right="\u00f3", ul="\u00f4", ur="\u00f6", dl="\u00f5", dr="\u00f8"),
            FlickKey("p", up="0", down="%", left="\u03c0", right="\u00a7", ul="\u00a3", ur="\u00b6", dl="\u00bf", dr="\u00a2"),
        ),
        // Row 1: a  s  d  f  g  h  j  k  l
        listOf(
            FlickKey("a", up="@", down="\u00e0", left="\u00e1", right="\u00e2", ul="\u00e4", ur="\u00e3", dl="\u00e5", dr="\u00e6"),
            FlickKey("s", up="$", down="\u00df", left="/",  right="\\", ul="\u015b", ur="\u0161", dl="\u015f", dr="\u0219"),
            FlickKey("d", up="#", down="\u00f0", left=":",  right=";",  ul="\u010f", ur="\u0111", dl="\u201e", dr="\u201d"),
            FlickKey("f", up="!", down="\u0192", left="\u2018", right="\u2019", ul="\u201c", ur="\u00ab", dl="\u00bb", dr="\u2039"),
            FlickKey("g", up="&", down="\u011f", left="{",  right="}",  ul="\u011d", ur="\u0121", dl="\u0123", dr="\u03b3"),
            FlickKey("h", up="*", down="\u0127", left="-",  right="+",  ul="\u0125", ur="\u1e25", dl="\u00b2", dr="\u00b3"),
            FlickKey("j", up="=", down="\u0135", left="<",  right=">",  ul="\u201e", ur="\u201d", dl="\u201a", dr="\u2019"),
            FlickKey("k", up="(", down="\u0137", left="[",  right="]",  ul="\u03ba", ur="\u0138", dl="\u221a", dr="\u221e"),
            FlickKey("l", up=")", down="\u0142", left="\u03bb", right="\u00a3", ul="\u013a", ur="\u013c", dl="\u013e", dr="\u2113"),
        ),
        // Row 2: null=shift, z x c v b n m, null=backspace
        listOf(
            null,
            FlickKey("z", up="!", down="\u017a", left="\u03b6", right="\u017c", ul="\u017e", ur="\u2124", dl="\u2070", dr="\u00b9"),
            FlickKey("x", up="?", down="\u00d7", left="\u03be", right="\u03c7", ul="\u207b", ur="\u207a", dl="\u2081", dr="\u2082"),
            FlickKey("c", up=":", down="\u00e7", left="\u0107", right="\u010d", ul="\u00a9", ur="\u00a2", dl="\u0109", dr="\u010b"),
            FlickKey("v", up=";", down="\u221a", left="\u2039", right="\u203a", ul="\u00ab", ur="\u00bb", dl="\u2228", dr="\u2227"),
            FlickKey("b", up="-", down="\u03b2", left="(",  right=")",  ul="[",  ur="]", dl="\u222b", dr="\u2202"),
            FlickKey("n", up="_", down="\u00f1", left="\u0144", right="\u0148", ul="\u0146", ur="\u014b", dl="\u03bd", dr="\u2229"),
            FlickKey("m", up="*", down="\u00b5", left="<",  right=">",  ul="(",  ur=")", dl="\u2208", dr="\u2211"),
            null,
        )
    )
    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dbHelper = DictionaryDatabaseHelper(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        // Reload layout when user changes character toggles in Settings
        android.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener { _, key ->
                if (key?.startsWith("char_enabled_") == true) refreshKeyboardModeUI()
            }
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

        keyboardView.keyActionListener = { r, c, gesture -> handleAction(r, c, gesture) }
        keyboardView.backspaceListener  = { handleBackspace() }
        keyboardView.longPressListener  = { r, c ->
            val isPickerCell = (!qwertyActive && r == 2 && c == 4) ||
                               (qwertyActive && r == 3 && c == 5)
            if (isPickerCell) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }

        suggestions.forEach { tv ->
            tv.setOnClickListener { v ->
                val suggestion = (v as TextView).text.toString()
                if (suggestion.isNotEmpty()) commitWord(applyCase(suggestion))
            }
        }

        updatePasteButtonVisibility()
        refreshKeyboardModeUI()
        return root
    }

    // ─── Action routing ───────────────────────────────────────────────────────

    private fun handleAction(r: Int, c: Int, gesture: Gesture) {
        if (qwertyActive) {
            handleQwertyAction(r, c, gesture)
        } else {
            handleBasqueAction(r, c, gesture)
        }
    }

    // Moves the cursor by `delta` characters (-1 = left, +1 = right) without
    // sending a D-pad key event. D-pad events can steal focus when the cursor
    // is already at the start/end of the field; setSelection() never does.
    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        val info = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val sel = info.selectionStart
        val len = info.text?.length ?: 0
        val newPos = (sel + delta).coerceIn(0, len)
        ic.setSelection(newPos, newPos)
    }

    private fun handleBasqueAction(r: Int, c: Int, gesture: Gesture) {
        val ic = currentInputConnection ?: return
        when {
            r == 2 && c == 0 -> handleShiftTap()
            r == 1 && c == 0 -> { commitCurrent(); moveCursor(-1); updateCapsMode() }
            r == 1 && c == 4 -> { commitCurrent(); moveCursor(+1); updateCapsMode() }
            r == 2 && c == 4 -> { commitCurrent(); ic.commitText(" ", 1); updateCapsMode() }
            r == 3 && c == 4 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); updateCapsMode() }
            r == 3 && c == 0 -> cycleMode()
            r == 3 && c == 1 -> performMutation()
            else -> handleCharInput(r, c, gesture)
        }
    }

    private fun handleQwertyAction(r: Int, c: Int, gesture: Gesture) {
        val ic = currentInputConnection ?: return
        when {
            r == 2 && c == 0 -> handleShiftTap()
            r == 3 && c == 0 -> cycleMode()
            r == 3 && c == 1 -> { commitCurrent(); ic.commitText(",", 1); updateCapsMode() }
            r == 3 && c == 2 -> { commitCurrent(); ic.commitText(" ", 1); updateCapsMode() }
            r == 3 && c == 4 -> { commitCurrent(); ic.commitText(".", 1); updateCapsMode() }
            r == 3 && c == 5 -> { commitCurrent(); moveCursor(-1); updateCapsMode() }
            r == 3 && c == 6 -> { commitCurrent(); moveCursor(+1); updateCapsMode() }
            r == 3 && c == 7 -> { commitCurrent(); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); updateCapsMode() }
            else -> {
                val key = qwertyLayout.getOrNull(r)?.getOrNull(c) ?: return
                val raw = key.getChar(gesture) ?: return
                val isLetter = raw.any { it.isLetter() }
                // Letters respect case mode; symbols/digits are committed as-is
                var s = if (isLetter) applyCase(raw) else raw
                if (isLetter && currentMode == KeyboardMode.TITLE) {
                    currentMode = KeyboardMode.LOWER
                    refreshKeyboardModeUI()
                }
                ic.beginBatchEdit()
                if (!s.any { it.isLetter() }) commitCurrent()
                else if (composingWord.isNotEmpty() && !composingWord.any { it.isLetter() }) commitCurrent()
                composingWord.append(s)
                lastInput = s
                lastInputMode = if (isLetter) currentMode else KeyboardMode.LOWER
                updateUI()
                ic.endBatchEdit()
            }
        }
    }

    // ─── Shift / UPPER ────────────────────────────────────────────────────────

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

    // ─── Character input (Basque / Num) ───────────────────────────────────────

    private fun handleCharInput(r: Int, c: Int, gesture: Gesture) {
        val ic = currentInputConnection ?: return
        val layout = if (currentMode == KeyboardMode.NUM) numLayout else basqueLayout
        val key = layout[Pair(r, c)] ?: return
        var s = key.getChar(gesture) ?: return

        s = applyCase(s)
        if (currentMode == KeyboardMode.TITLE && s.any { it.isLetter() }) {
            currentMode = KeyboardMode.LOWER
            refreshKeyboardModeUI()
        }

        ic.beginBatchEdit()
        if (!s.any { it.isLetter() }) commitCurrent()
        else if (composingWord.isNotEmpty() && !composingWord.any { it.isLetter() }) commitCurrent()
        composingWord.append(s)
        lastInput = s
        lastInputMode = currentMode
        updateUI()
        ic.endBatchEdit()
    }

    // ─── Case ─────────────────────────────────────────────────────────────────

    private fun applyCase(s: String): String = when (currentMode) {
        KeyboardMode.UPPER -> s.uppercase()
        KeyboardMode.TITLE -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else               -> s.lowercase()
    }

    // ─── Commit ───────────────────────────────────────────────────────────────

    private fun commitCurrent() {
        if (composingWord.isNotEmpty()) {
            val committed = composingWord.toString()
            currentInputConnection?.commitText(committed, 1)
            lastCommittedWord = committed.trimEnd().lowercase()
            composingWord.setLength(0)
            lastInput = ""
            lastInputMode = KeyboardMode.LOWER
            updateUI()   // triggers bigram suggestions for the just-committed word
        }
    }

    private fun commitWord(word: String) {
        if (word.isNotEmpty()) {
            lastCommittedWord = word.trimEnd()
            currentInputConnection?.commitText("$word ", 1)
            composingWord.setLength(0)
            lastInput = ""
            lastInputMode = KeyboardMode.LOWER
            updateUI()
            updateCapsMode()
        }
    }

    // ─── Mutation ─────────────────────────────────────────────────────────────

    private fun getMutationTarget(input: String): String? {
        if (input.isEmpty()) return null
        val key = input.lowercase()

        // 1. Direct lookup (pure vowels, h-prefixed vowels, digraphs like "tz")
        mutationMap[key]?.let { return applyInputCase(it) }

        // 2. Syllable lookup: strip trailing vowel, look up consonant, reattach vowel
        val vowelRegex = "[aeiouüAEIOUÜ]$".toRegex()
        val vowelMatch = vowelRegex.find(input)
        if (vowelMatch != null) {
            val vowel = vowelMatch.value
            val base  = input.dropLast(vowel.length)
            if (base.isNotEmpty()) {
                mutationMap[base.lowercase()]?.let { return applyInputCase(it) + vowel }
            }
        }

        return null
    }

    // Case is determined by the mode active when lastInput was typed, not by inspecting
    // the character — so "A" typed in TITLE and UPPER are correctly distinguished.
    private fun applyInputCase(targetLower: String): String = when (lastInputMode) {
        KeyboardMode.UPPER -> targetLower.uppercase()
        KeyboardMode.TITLE -> targetLower.replaceFirstChar { it.uppercase() }
        else               -> targetLower
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
        val ic   = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        // NUM and UPPER are never auto-switched by caps mode.
        // QWERTY is allowed to transition between LOWER and TITLE (capitalisation),
        // but updateCapsMode must never switch *away* from QWERTY to a different layout.
        if (currentMode == KeyboardMode.NUM || currentMode == KeyboardMode.UPPER) return

        val caps = ic.getCursorCapsMode(info.inputType)
        val shouldCap = (caps and (TextUtils.CAP_MODE_SENTENCES or
                                   TextUtils.CAP_MODE_CHARACTERS or
                                   TextUtils.CAP_MODE_WORDS)) != 0

        if (composingWord.isEmpty()) {
            // In QWERTY we only ever toggle between LOWER and TITLE — never leave QWERTY.
            // In Basque we do the same but lastMode also drives the cycle-back after NUM.
            val newMode = if (shouldCap) KeyboardMode.TITLE else KeyboardMode.LOWER
            if (currentMode != newMode) {
                currentMode = newMode
                // Keep lastMode in sync so cycle-back from NUM returns to the right state,
                // but only when we are in Basque mode (lastMode drives cycle-back from NUM).
                if (!qwertyActive) lastMode = newMode
                refreshKeyboardModeUI()
            }
        }
    }

    // ─── Suggestions ──────────────────────────────────────────────────────────

    private fun updateUI() {
        val ic   = currentInputConnection ?: return
        val word = composingWord.toString()
        ic.setComposingText(word, 1)

        // Increment sequence so any in-flight task from a previous call is discarded.
        val seq = ++suggestionSeq

        dbExecutor.execute {
            val preds: List<String> = when {
                word.isNotEmpty() -> {
                    val prefix = word.lowercase()
                    // Only query emojis once prefix is long enough to be meaningful,
                    // and cap emoji slots at 2 so words always get at least 3 slots.
                    val emojis = if (prefix.length >= 3)
                        dbHelper.getEmojiSuggestions(prefix, limit = 2)
                    else emptyList()
                    val words = dbHelper.getSuggestions(prefix, limit = 5 - emojis.size)
                    // Words first, emojis appended — so word suggestions are never crowded out
                    words + emojis
                }
                lastCommittedWord.isNotEmpty() -> dbHelper.getBigramSuggestions(lastCommittedWord)
                else                           -> emptyList()
            }
            uiHandler.post {
                // Only apply if this is still the most recent request
                if (::suggestions.isInitialized && seq == suggestionSeq) {
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
        keyboardView.currentMode = currentMode
        keyboardView.qwertyActive = qwertyActive
        keyboardView.qwertyFlickLayout = qwertyLayout
        // layoutMap is only used for Basque/Num; QWERTY has its own draw path
        val rawLayout = if (currentMode == KeyboardMode.NUM) numLayout else basqueLayout
        keyboardView.layoutMap = KeyFilter.applyToLayout(this, rawLayout)
        keyboardView.modeLabel = when {
            qwertyActive              -> getString(R.string.mode_qwerty)
            currentMode == KeyboardMode.NUM   -> getString(R.string.mode_number)
            currentMode == KeyboardMode.UPPER -> getString(R.string.mode_upper)
            currentMode == KeyboardMode.TITLE -> getString(R.string.mode_title)
            else                              -> getString(R.string.mode_lower)
        }
        keyboardView.invalidate()
    }

    private fun updatePasteButtonVisibility() {
        if (!::pasteButton.isInitialized) return
        pasteButton.visibility = if (clipboardManager.hasPrimaryClip()) View.VISIBLE else View.GONE
    }

    // ─── Mode cycling ─────────────────────────────────────────────────────────

    private fun cycleMode() {
        when {
            qwertyActive -> {
                // QWERTY -> back to Basque (restore lastMode)
                qwertyActive = false
                currentMode = lastMode.takeIf { it != KeyboardMode.NUM } ?: KeyboardMode.LOWER
            }
            currentMode == KeyboardMode.NUM -> {
                // NUM -> QWERTY (keep current case mode)
                qwertyActive = true
            }
            else -> {
                // Basque LOWER/TITLE/UPPER -> NUM
                lastMode = currentMode
                currentMode = KeyboardMode.NUM
            }
        }
        refreshKeyboardModeUI()
    }

    // ─── InputMethodService callbacks ─────────────────────────────────────────

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingWord.setLength(0)
        lastInput = ""
        lastInputMode = KeyboardMode.LOWER
        lastCommittedWord = ""
        suggestionSeq++
        // Clear synchronously if the view exists; otherwise it will be cleared in onStartInputView.
        if (::suggestions.isInitialized) suggestions.forEach { it.text = "" }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composingWord.setLength(0)
        lastInput = ""
        lastInputMode = KeyboardMode.LOWER
        if (::suggestions.isInitialized) suggestions.forEach { it.text = "" }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Clear suggestion strip synchronously — no DB round-trip needed on startup.
        if (::suggestions.isInitialized) suggestions.forEach { it.text = "" }
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
            lastInputMode = KeyboardMode.LOWER
            updateUI()
        }
        updateCapsMode()
    }

    private fun handleBackspace() {
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            lastInput = ""
            lastInputMode = KeyboardMode.LOWER
            updateUI()
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            updateCapsMode()
        }
    }
}
