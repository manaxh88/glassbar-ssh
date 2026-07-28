package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class TerminalInputView(context: Context) : View(context) {

    var onSendInput: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Use TYPE_CLASS_TEXT with visible password to disable dictionary suggestions,
        // while still forcing standard text input behavior.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or 
                             InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or 
                             InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or 
                              EditorInfo.IME_FLAG_NO_EXTRACT_UI
                              
        return object : BaseInputConnection(this, true) {

            // Always pretend we have text so the backspace key is never disabled by the IME
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                return " ".repeat(n.coerceAtLeast(1).coerceAtMost(20))
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
                return ""
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {

                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            onSendInput?.invoke("\u007F")
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            onSendInput?.invoke("\r")
                            return true
                        }
                        KeyEvent.KEYCODE_TAB -> {
                            onSendInput?.invoke("\t")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onSendInput?.invoke("\u001B[A")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onSendInput?.invoke("\u001B[B")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSendInput?.invoke("\u001B[D")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSendInput?.invoke("\u001B[C")
                            return true
                        }
                        else -> {
                            val char = event.unicodeChar
                            if (char != 0) {
                                onSendInput?.invoke(char.toChar().toString())
                                return true
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {

                val superRet = super.commitText(text, newCursorPosition)
                if (!text.isNullOrEmpty()) {
                    onSendInput?.invoke(text.toString())
                }
                
                // Clear the internal editable and pad with a space to keep backspace active
                editable?.clear()
                editable?.append(" ")
                android.text.Selection.setSelection(editable, 1)
                
                return superRet
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {

                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onSendInput?.invoke("\u007F")
                    }
                }
                editable?.clear()
                editable?.append(" ")
                android.text.Selection.setSelection(editable, 1)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {

                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onSendInput?.invoke("\u007F")
                    }
                }
                editable?.clear()
                editable?.append(" ")
                android.text.Selection.setSelection(editable, 1)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {

                return super.setComposingText(text, newCursorPosition)
            }
        }
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}
