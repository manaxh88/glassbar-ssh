package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.ui.graphics.Color

data class TerminalCell(
    var char: Char = ' ',
    var fgColor: Color? = null,
    var bgColor: Color? = null,
    var isBold: Boolean = false
) {
    fun reset() {
        char = ' '
        fgColor = null
        bgColor = null
        isBold = false
    }

    fun copyFrom(other: TerminalCell) {
        char = other.char
        fgColor = other.fgColor
        bgColor = other.bgColor
        isBold = other.isBold
    }
}
