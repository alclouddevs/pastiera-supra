package it.palsoftware.pastiera.inputmethod

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Tracks text committed by the IME and provides a real undo stack.
 *
 * Undo units are grouped by word: consecutive non-space characters form one unit,
 * and spaces/newlines each form their own unit. This gives word-level undo granularity.
 *
 * Integration:
 *  - Call [onCharTyped] for every printable character that reaches the text field via sendKeyEvent.
 *  - Call [onTextCommitted] for every explicit commitText call (auto-correct, variations, etc.).
 *  - Call [onBackspace] when backspace is pressed.
 *  - Call [onCursorMoved] when the cursor is repositioned (arrows, home/end, etc.) so the
 *    pending word is finalised and the undo boundary is reset.
 *  - Call [clearSession] when switching to a new text field.
 *  - Call [undo] to perform the actual undo against the InputConnection.
 */
class InputUndoManager {

    private val stack = ArrayDeque<String>()
    private val pendingWord = StringBuilder()

    companion object {
        private const val TAG = "UndoStack"
        private const val MAX_STACK_SIZE = 100
    }

    /** Called for each printable character typed via the hardware keyboard (sendKeyEvent path). */
    fun onCharTyped(char: Char) {
        Log.d(TAG, "onCharTyped: '$char'  pending='$pendingWord'  stack.size=${stack.size}")
        when (char) {
            ' ', '\n', '\r', '\t' -> {
                pendingWord.append(char)
                commitPending()
            }
            else -> pendingWord.append(char)
        }
    }

    /**
     * Called when the IME explicitly commits text via commitText
     * (auto-correct, variation chars, multi-tap, etc.).
     */
    fun onTextCommitted(text: String) {
        if (text.isEmpty()) return
        Log.d(TAG, "onTextCommitted: '$text'  stack.size=${stack.size}")
        commitPending()
        push(text)
    }

    /**
     * Called when backspace is pressed.
     * Trims the pending word first; if empty, trims the last stack entry.
     */
    fun onBackspace() {
        if (pendingWord.isNotEmpty()) {
            pendingWord.deleteCharAt(pendingWord.length - 1)
        } else {
            val last = stack.removeLastOrNull() ?: return
            if (last.length > 1) {
                stack.addLast(last.dropLast(1))
            }
        }
    }

    /**
     * Called when the cursor is moved (arrow keys, home/end, page up/down).
     * Finalises the current pending word so the undo boundary is reset.
     */
    fun onCursorMoved() {
        commitPending()
    }

    /**
     * Returns how many undo steps are available (pending word counts as 1).
     */
    fun stackSize(): Int = stack.size + (if (pendingWord.isNotEmpty()) 1 else 0)

    /**
     * Performs one undo step: sends DEL key events for each character in the last
     * recorded text chunk. Uses sendKeyEvent — the same deletion path as the physical
     * backspace key — rather than deleteSurroundingText for maximum reliability.
     *
     * Returns true if undo was applied, false if the stack was empty.
     */
    fun undo(ic: InputConnection): Boolean {
        commitPending()
        Log.d(TAG, "undo called: stack.size=${stack.size}")
        val text = stack.removeLastOrNull() ?: run {
            Log.d(TAG, "undo: stack empty, nothing to undo")
            return false
        }
        if (text.isEmpty()) return false
        Log.d(TAG, "undo: sending ${text.length} DEL events for '${text}'")
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
        repeat(text.length) {
            ic.sendKeyEvent(downEvent)
            ic.sendKeyEvent(upEvent)
        }
        return true
    }

    /** Clears all history. Call this when the user moves to a new text field. */
    fun clearSession() {
        Log.d(TAG, "clearSession")
        stack.clear()
        pendingWord.clear()
    }

    private fun commitPending() {
        val word = pendingWord.toString()
        if (word.isNotEmpty()) {
            push(word)
            pendingWord.clear()
        }
    }

    private fun push(text: String) {
        stack.addLast(text)
        while (stack.size > MAX_STACK_SIZE) stack.removeFirst()
    }
}
