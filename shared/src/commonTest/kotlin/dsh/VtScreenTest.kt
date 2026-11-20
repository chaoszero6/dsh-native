package dsh

import dsh.term.VtScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VtScreenTest {
    private fun VtScreen.row(y: Int) = lines()[y].map { it.ch }.joinToString("").trimEnd()

    @Test fun printsAndWraps() {
        val s = VtScreen(5, 3)
        s.feed("hello world")
        assertEquals("hello", s.row(0)); assertEquals(" worl", s.row(1)); assertEquals("d", s.row(2))
    }

    @Test fun crLfAndScrollIntoScrollback() {
        val s = VtScreen(10, 2)
        s.feed("a\r\nb\r\nc")
        assertEquals("b", s.row(0)); assertEquals("c", s.row(1))
        assertEquals(1, s.scrollbackSize)
        assertTrue(s.text().startsWith("a\nb\nc"))
    }

    @Test fun cursorMovesAndErase() {
        val s = VtScreen(10, 3)
        s.feed("xxxxxxxxxx\u001B[1;3HAB\u001B[K")
        assertEquals("xxAB", s.row(0))
        s.feed("\u001B[2J\u001B[H*")
        assertEquals("*", s.row(0)); assertEquals(0, s.cursorY); assertEquals(1, s.cursorX)
    }

    @Test fun sgrColors() {
        val s = VtScreen(10, 1)
        s.feed("\u001B[1;31mR\u001B[0m\u001B[38;5;200mP\u001B[38;2;1;2;3mT\u001B[44mB")
        val l = s.lines()[0]
        assertEquals(1, l[0].style.fg); assertTrue(l[0].style.bold)
        assertEquals(200, l[1].style.fg)
        assertEquals(0x1000000 or (1 shl 16) or (2 shl 8) or 3, l[2].style.fg)
        assertEquals(4, l[3].style.bg)
    }

    @Test fun alternateScreenRestoresMain() {
        val s = VtScreen(10, 2)
        s.feed("main")
        s.feed("\u001B[?1049h")
        assertTrue(s.isAltScreen); assertEquals("", s.row(0))
        s.feed("vim")
        s.feed("\u001B[?1049l")
        assertEquals("main", s.row(0))
    }

    @Test fun scrollRegionAndInsertDelete() {
        val s = VtScreen(4, 4)
        s.feed("1\r\n2\r\n3\r\n4")
        s.feed("\u001B[2;3r\u001B[2;1H\u001B[M")
        assertEquals(listOf("1", "3", "", "4"), (0..3).map { s.row(it) })
        s.feed("\u001B[1;1H\u001B[2P")
        s.resize(6, 4)
        assertEquals(6, s.cols)
    }
}
