package dev.warpterminal.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBlockTest {
    @Test fun progressReplacesOnlyTheLiveLineAcrossFrames() {
        val block = CommandBlock(1, "apt")
        block.append(Output("前一行\nReading 0%", null, false))
        val revision = block.outputRevision
        block.editLine("column", 0)
        block.editLine("clear_right", 0)
        block.append(Output("Reading ", null, false))
        block.append(Output("100%", 0x102030, true))
        block.editLine("column", 0)
        block.append(Output("\n", null, false))
        assertEquals("前一行\nReading 100%\n", block.lines.joinToString("") { it.text })
        assertTrue(block.lines.contains(Output("100%", 0x102030, true)))
        assertTrue(block.outputRevision > revision)
    }

    @Test fun shorterOverwriteKeepsTheSuffixUntilErased() {
        val block = CommandBlock(1, "printf")
        block.append(Output("abcdef", null, false))
        block.editLine("column", 0)
        block.append(Output("XY", null, false))
        assertEquals("XYcdef", block.lines.joinToString("") { it.text })
        block.editLine("backspace", 0)
        block.editLine("clear_right", 0)
        block.append(Output("😀", null, false))
        assertEquals("X😀", block.lines.joinToString("") { it.text })
    }
}
