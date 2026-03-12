package com.example.flickkeyboard

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import android.view.inputmethod.EditorInfo


class FlickKeyboardService : InputMethodService() {

    private lateinit var keyboardView: FlickKeyboardView
    private lateinit var sug1: TextView
    private lateinit var sug2: TextView
    private lateinit var sug3: TextView
    private lateinit var sug4: TextView
    private lateinit var sug5: TextView
    private var composingWord = StringBuilder()
    private var currentMode = KeyboardMode.LOWER

    private val mutationMap = mapOf(
        "k" to "g", "g" to "k", "z" to "tz", "tz" to "z",
        "t" to "d", "d" to "t", "n" to "ñ", "ñ" to "n",
        "p" to "b", "b" to "p", "s" to "ts", "ts" to "s",
        "x" to "tx", "tx" to "x", "l" to "r", "r" to "l",
	"j" to "y", "y" to "j"
    )

    private val basqueDict = mapOf("kaixo" to 100, "egun" to 90, "on" to 85, "eta" to 80, "ba" to 70, "ez" to 60, "baina" to 50)

    private lateinit var dbHelper: DictionaryDatabaseHelper
    private val uiHandler = Handler(Looper.getMainLooper())

    private val basqueLayout = mapOf(
        Pair(0,0) to FlickKey("sa","su","so","si","se", ur="s"),
        Pair(0,1) to FlickKey("a","u","o","i","e"),
        Pair(0,2) to FlickKey("ga","gu","go","gi","ge", ur="k"),
        Pair(0,3) to FlickKey("za","zu","zo","zi","ze", ur="z"),
        Pair(1,1) to FlickKey("da","du","do","di","de", ur="t"),
        Pair(1,2) to FlickKey("na","nu","no","ni","ne", ur="n"),
        Pair(1,3) to FlickKey("ba","bu","bo","bi","be", ur="p"),
        Pair(2,0) to FlickKey("xa","xu","xo","xi","xe", ur="x"),
        Pair(2,1) to FlickKey("ma","mu","mo","mi","me", ur="m"),
        Pair(2,2) to FlickKey("ja","ju","jo","ji","je", ur="j"),
        Pair(2,3) to FlickKey("la","lu","lo","li","le", ur="l"),
        Pair(3,2) to FlickKey("ha","hu","ho","hi","he", ur="h"),
        Pair(3,3) to FlickKey(", ","? ",". ",": ","! ", ur="; ")
    )

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_layout, null)
        keyboardView = root.findViewById(R.id.flick_keyboard_view)
        sug1 = root.findViewById(R.id.sug1)
	sug2 = root.findViewById(R.id.sug2)
	sug3 = root.findViewById(R.id.sug3)
	sug4 = root.findViewById(R.id.sug4)
	sug5 = root.findViewById(R.id.sug5)
        
        keyboardView.layoutMap = basqueLayout
        keyboardView.keyActionListener = { r, c, d -> handleAction(r, c, d) }
        keyboardView.backspaceListener = { handleBackspace() }
        keyboardView.longPressListener = { r, c -> if (r==2 && c==4) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        
        val sL = View.OnClickListener { v -> commitWord((v as TextView).text.toString()) }
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

        if (composingWord.isEmpty()) {
            if (shouldCapitalize && currentMode != KeyboardMode.UPPER) {
                currentMode = KeyboardMode.UPPER
                refreshKeyboardModeUI()
            } else if (!shouldCapitalize && currentMode == KeyboardMode.UPPER) {
                currentMode = KeyboardMode.LOWER
                refreshKeyboardModeUI()
            }
        }
    }

    private fun refreshKeyboardModeUI() {
        keyboardView.isUppercase = (currentMode == KeyboardMode.UPPER)
        keyboardView.modeLabel = when(currentMode) { KeyboardMode.LOWER -> "abc"; KeyboardMode.UPPER -> "ABC"; else -> "?123" }
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
        keyboardView.swapLabel = if (lastC.isNotEmpty()) "$lastC→${mutationMap[lastC]}" else "⟳"
        keyboardView.invalidate()
    }

    private fun commitCurrent() { 
        if (composingWord.isNotEmpty()) { 
            currentInputConnection?.commitText(composingWord.toString(), 1)
            composingWord.clear()
            updateUI()
            updateCapsMode() // Check for caps after committing (e.g., after a period)
        } 
    }
    
    private fun commitWord(w: String) { 
        if (w.isNotEmpty()) { 
            currentInputConnection?.commitText("$w ", 1)
            composingWord.clear()
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
    }

    private fun cycleMode() {
        currentMode = when(currentMode) { KeyboardMode.LOWER -> KeyboardMode.UPPER; KeyboardMode.UPPER -> KeyboardMode.NUM; else -> KeyboardMode.LOWER }
        refreshKeyboardModeUI()
    }

    private fun getChar(r: Int, c: Int, d: FlickDirection): String? {
        val k = basqueLayout[Pair(r,c)] ?: return null
        val charStr = when(d) { 
            FlickDirection.TAP -> k.tap
            FlickDirection.UP -> k.up
            FlickDirection.RIGHT -> k.right
            FlickDirection.LEFT -> k.left
            FlickDirection.DOWN -> k.down
            FlickDirection.UP_RIGHT -> k.ur
            FlickDirection.DOWN_RIGHT -> k.down
            FlickDirection.DOWN_LEFT -> k.left
            FlickDirection.UP_LEFT -> k.up
        }
        
        // Apply uppercase if mode is UPPER
        return if (currentMode == KeyboardMode.UPPER) charStr?.uppercase() else charStr
    }
}
