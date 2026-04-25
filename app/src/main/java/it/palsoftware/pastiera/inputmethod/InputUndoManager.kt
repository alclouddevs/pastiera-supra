package it.palsoftware.pastiera.inputmethod

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Tracks text committed by the IME and provides a smart undo stack.
 *
 * Chunk boundaries (each chunk = one undo step):
 *  - A pause of [CHUNK_BREAK_MS] or more between keystrokes starts a new chunk.
 *  - A space or newline flushes the current chunk (space included in the chunk).
 *  - Auto-corrected / explicitly committed text is always its own chunk.
 *  - Cursor repositioning clears history (position is now unknown).
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
    private val pendingChunk = StringBuilder()
    private var lastKeyTime = 0L

    companion object {
        private const val TAG = "UndoStack"
        private const val MAX_STACK_SIZE = 200
        private const val CHUNK_BREAK_MS = 1000L
    }

    /**
     * Called for each printable character typed via the hardware keyboard.
     * Flushes the current chunk on space/newline or if more than [CHUNK_BREAK_MS]
     * has elapsed since the last keystroke.
     */
    fun onCharTyped(char: Char) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastKeyTime

        if (lastKeyTime != 0L && elapsed >= CHUNK_BREAK_MS) {
            commitPending()
            Log.d(TAG, "onCharTyped: time break (${elapsed}ms), flushed chunk")
        }

        lastKeyTime = now

        when (char) {
            ' ', '\n', '\r', '\t' -> {
                pendingChunk.append(char)
                commitPending()
            }
            else -> {
                pendingChunk.append(char)
            }
        }

        Log.d(TAG, "onCharTyped: '$char'  pending='$pendingChunk'  stack.size=${stack.size}")
    }

    /**
     * Called when the IME explicitly commits text via commitText
     * (auto-correct, variation chars, etc.). The whole committed string
     * is treated as a single undo step.
     */
    fun onTextCommitted(text: String) {
        if (text.isEmpty()) return
        Log.d(TAG, "onTextCommitted: '$text'  stack.size=${stack.size}")
        commitPending()
        push(text)
        lastKeyTime = 0L
    }

    /**
     * Called when backspace is pressed. Trims the pending chunk first;
     * if empty, trims the last stack entry to stay in sync with the field.
     */
    fun onBackspace() {
        if (pendingChunk.isNotEmpty()) {
            pendingChunk.deleteCharAt(pendingChunk.length - 1)
            lastKeyTime = System.currentTimeMillis()
        } else {
            val last = stack.removeLastOrNull() ?: return
            if (last.length > 1) {
                stack.addLast(last.dropLast(1))
            }
        }
    }

    /**
     * Called when the cursor is moved (arrow keys, home/end, page up/down).
     * Clears all history because the cursor position is now unknown.
     */
    fun onCursorMoved() {
        Log.d(TAG, "onCursorMoved: clearing stack")
        stack.clear()
        pendingChunk.clear()
        lastKeyTime = 0L
    }

    /** Returns how many undo steps are available (pending chunk counts as 1). */
    fun stackSize(): Int = stack.size + (if (pendingChunk.isNotEmpty()) 1 else 0)

    /**
     * Performs one undo step: flushes any pending chunk then deletes the last
     * recorded chunk from the field using deleteSurroundingText.
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
        Log.d(TAG, "undo: deleteSurroundingText(${text.length}) for '${text}'")
        ic.beginBatchEdit()
        ic.deleteSurroundingText(text.length, 0)
        ic.endBatchEdit()
        lastKeyTime = 0L
        return true
    }

    /** Clears all history. Call when switching to a new text field. */
    fun clearSession() {
        Log.d(TAG, "clearSession")
        stack.clear()
        pendingChunk.clear()
        lastKeyTime = 0L
    }

    private fun commitPending() {
        val chunk = pendingChunk.toString()
        if (chunk.isNotEmpty()) {
            push(chunk)
            pendingChunk.clear()
        }
    }

    private fun push(text: String) {
        stack.addLast(text)
        while (stack.size > MAX_STACK_SIZE) stack.removeFirst()
    }
}
