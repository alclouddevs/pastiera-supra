package it.palsoftware.pastiera.inputmethod

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Tracks text committed by the IME and provides a real undo stack.
 *
 * Granularity:
 *  - Each typed character is its own undo step (one press = one char deleted).
 *  - Auto-corrected or explicitly committed text (commitText) is one undo step
 *    per commit, since replacing/inserting a whole word should be undone at once.
 *  - Backspace adjusts the stack to stay in sync with the actual field content.
 *
 * Integration:
 *  - Call [onCharTyped] for every printable character that reaches the text field.
 *  - Call [onTextCommitted] for every explicit commitText call (auto-correct, etc.).
 *  - Call [onBackspace] when the backspace key is pressed.
 *  - Call [onCursorMoved] when the cursor is repositioned (arrows, home/end, etc.).
 *  - Call [clearSession] when switching to a new text field.
 *  - Call [undo] to perform one undo step against the InputConnection.
 */
class InputUndoManager {

    private val stack = ArrayDeque<String>()

    companion object {
        private const val TAG = "UndoStack"
        private const val MAX_STACK_SIZE = 200
    }

    /**
     * Called for each printable character typed via the hardware keyboard.
     * Each character becomes its own undo step immediately.
     */
    fun onCharTyped(char: Char) {
        Log.d(TAG, "onCharTyped: '$char'  stack.size=${stack.size}")
        push(char.toString())
    }

    /**
     * Called when the IME explicitly commits text via commitText
     * (auto-correct, variation chars, etc.). The whole committed string
     * is treated as a single undo step.
     */
    fun onTextCommitted(text: String) {
        if (text.isEmpty()) return
        Log.d(TAG, "onTextCommitted: '$text'  stack.size=${stack.size}")
        push(text)
    }

    /**
     * Called when backspace is pressed. Pops or trims the last stack entry
     * to stay in sync with what was actually deleted from the field.
     */
    fun onBackspace() {
        val last = stack.removeLastOrNull() ?: return
        if (last.length > 1) {
            stack.addLast(last.dropLast(1))
        }
    }

    /**
     * Called when the cursor is moved (arrow keys, home/end, page up/down).
     * Clears undo history because we can no longer reliably predict what is
     * immediately before the cursor after a repositioning.
     */
    fun onCursorMoved() {
        Log.d(TAG, "onCursorMoved: clearing stack")
        stack.clear()
    }

    /**
     * Returns how many undo steps are available.
     */
    fun stackSize(): Int = stack.size

    /**
     * Performs one undo step: deletes the last recorded text chunk from the field
     * using deleteSurroundingText (avoids looping back through onKeyDown).
     *
     * Returns true if undo was applied, false if the stack was empty.
     */
    fun undo(ic: InputConnection): Boolean {
        Log.d(TAG, "undo called: stack.size=${stack.size}")
        val text = stack.removeLastOrNull() ?: run {
            Log.d(TAG, "undo: stack empty, nothing to undo")
            return false
        }
        if (text.isEmpty()) return false
        Log.d(TAG, "undo: deleteSurroundingText(${text.length}) for '${text}'")
        ic.beginBatchEdit()
        ic.deleteSurroundingText(text.length, 0)
        ic.endBatchEdit()
        return true
    }

    /** Clears all history. Call when switching to a new text field. */
    fun clearSession() {
        Log.d(TAG, "clearSession")
        stack.clear()
    }

    private fun push(text: String) {
        stack.addLast(text)
        while (stack.size > MAX_STACK_SIZE) stack.removeFirst()
    }
}
