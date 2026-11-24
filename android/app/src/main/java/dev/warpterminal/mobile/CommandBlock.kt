package dev.warpterminal.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class Output(val text: String, val foreground: Int?, val bold: Boolean)

internal class CommandBlock(val id: Int, val command: String) {
    private data class Style(val foreground: Int?, val bold: Boolean)
    private data class Cell(val character: Int, val style: Style)

    private val completed = mutableStateListOf<Output>()
    private val row = mutableListOf<Cell>()
    private var visibleRow by mutableStateOf(emptyList<Output>())
    private var completedLength = 0
    private var column = 0
    val lines: List<Output> get() = completed + visibleRow
    var outputRevision by mutableIntStateOf(0)
        private set
    var truncated by mutableStateOf(false)
        private set
    var code by mutableStateOf<Int?>(null)

    fun append(output: Output) {
        val style = Style(output.foreground, output.bold)
        val finished = mutableListOf<Cell>()
        var offset = 0
        while (offset < output.text.length) {
            val character = output.text.codePointAt(offset)
            offset += Character.charCount(character)
            if (character == '\n'.code) {
                finished.addAll(row)
                completedLength += row.size
                if (completedLength < MAX_CHARACTERS) {
                    finished.add(Cell(character, style))
                    completedLength++
                } else {
                    truncated = true
                }
                row.clear()
                column = 0
            } else if (column < MAX_CHARACTERS - completedLength) {
                while (row.size <= column) row.add(blank)
                row[column++] = Cell(character, style)
            } else {
                truncated = true
                column = (column + 1).coerceAtMost(MAX_CHARACTERS)
            }
        }
        val before = completed.size
        completed.addAll(render(finished, MAX_RUNS - before))
        updateVisibleRow(completed.size != before)
    }

    fun editLine(operation: String, targetColumn: Int) {
        when (operation) {
            "column" -> column = targetColumn.coerceIn(0, MAX_CHARACTERS)
            "backspace" -> column = (column - 1).coerceAtLeast(0)
            "clear_right" -> {
                row.subList(column.coerceAtMost(row.size), row.size).clear()
                updateVisibleRow()
            }
            "clear_left" -> {
                for (index in 0 until minOf(row.size, column + 1)) row[index] = blank
                updateVisibleRow()
            }
            "clear_all" -> {
                row.clear()
                updateVisibleRow()
            }
        }
    }

    private fun updateVisibleRow(completedChanged: Boolean = false) {
        val next = render(row, MAX_RUNS - completed.size)
        if (next != visibleRow || completedChanged) {
            visibleRow = next
            outputRevision++
        }
    }

    private fun render(cells: List<Cell>, limit: Int): List<Output> = buildList {
        var start = 0
        while (start < cells.size && size < limit) {
            val style = cells[start].style
            val text = StringBuilder()
            do {
                text.appendCodePoint(cells[start++].character)
            } while (start < cells.size && cells[start].style == style)
            add(Output(text.toString(), style.foreground, style.bold))
        }
        if (start < cells.size) truncated = true
    }

    private companion object {
        const val MAX_CHARACTERS = 64 * 1024
        const val MAX_RUNS = 8_192
        val blank = Cell(' '.code, Style(null, false))
    }
}
