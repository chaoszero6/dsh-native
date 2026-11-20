package dsh.term

/**
 * Minimal VT100/xterm screen emulator, enough for shells, prompts, `ls --color`, `top`/`htop`-style redraws,
 * and alternate-screen programs. Feed it the server's `snapshot.screen` and subsequent `output.data` strings.
 */
class VtScreen(cols: Int, rows: Int, private val scrollbackLimit: Int = 2000) {
    data class Style(val fg: Int = -1, val bg: Int = -1, val bold: Boolean = false, val underline: Boolean = false, val inverse: Boolean = false, val dim: Boolean = false, val italic: Boolean = false)
    data class Cell(val ch: Char = ' ', val style: Style = DEFAULT)

    var cols = cols; private set
    var rows = rows; private set
    private var main = blank(rows)
    private var alt: Array<Array<Cell>>? = null
    private val scrollback = ArrayDeque<Array<Cell>>()
    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    private var savedX = 0; private var savedY = 0; private var savedStyle = DEFAULT
    private var style = DEFAULT
    private var top = 0; private var bottom = rows - 1
    private var wrapPending = false
    private var state = State.Normal
    private val params = StringBuilder()
    private var autoWrap = true
    /** Incremented on every change so UIs can cheaply detect updates. */
    var version = 0L; private set

    private enum class State { Normal, Esc, Csi, Osc, OscEsc, Charset }

    private val screen get() = alt ?: main
    val isAltScreen get() = alt != null

    private fun blank(n: Int) = Array(n) { blankLine() }
    private fun blankLine(st: Style = DEFAULT) = Array(cols) { Cell(' ', Style(bg = st.bg)) }

    fun reset() {
        main = blank(rows); alt = null; scrollback.clear()
        cursorX = 0; cursorY = 0; style = DEFAULT; top = 0; bottom = rows - 1; wrapPending = false; state = State.Normal
        version++
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        fun fit(src: Array<Array<Cell>>): Array<Array<Cell>> {
            val lines = src.toMutableList()
            while (lines.size > newRows) { if (src === main) pushScrollback(lines.removeAt(0)) else lines.removeAt(0) }
            while (lines.size < newRows) lines.add(Array(cols) { Cell() })
            return Array(newRows) { y -> Array(newCols) { x -> lines[y].getOrNull(x) ?: Cell() } }
        }
        val shift = (rows - newRows).coerceAtLeast(0)
        main = fit(main); alt = alt?.let { fit(it) }
        cols = newCols; rows = newRows
        cursorY = (cursorY - shift).coerceIn(0, rows - 1); cursorX = cursorX.coerceIn(0, cols - 1)
        top = 0; bottom = rows - 1
        version++
    }

    private fun pushScrollback(line: Array<Cell>) {
        scrollback.addLast(line)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    fun feed(data: String) {
        for (c in data) step(c)
        version++
    }

    private fun step(c: Char) {
        when (state) {
            State.Normal -> when (c) {
                '\u001B' -> state = State.Esc
                '\r' -> { cursorX = 0; wrapPending = false }
                '\n', '\u000B', '\u000C' -> lineFeed()
                '\b' -> { if (cursorX > 0) cursorX--; wrapPending = false }
                '\t' -> { cursorX = minOf(cols - 1, (cursorX / 8 + 1) * 8); wrapPending = false }
                '\u0007', '\u0000', '\u000E', '\u000F' -> Unit
                else -> if (c >= ' ') put(c)
            }
            State.Esc -> {
                state = State.Normal
                when (c) {
                    '[' -> { state = State.Csi; params.clear() }
                    ']' -> { state = State.Osc; params.clear() }
                    '(', ')', '*', '+' -> state = State.Charset
                    '7' -> saveCursor()
                    '8' -> restoreCursor()
                    'D' -> lineFeed()
                    'E' -> { cursorX = 0; lineFeed() }
                    'M' -> reverseIndex()
                    'c' -> reset()
                    else -> Unit
                }
            }
            State.Charset -> state = State.Normal
            State.Osc -> when (c) { '\u0007' -> state = State.Normal; '\u001B' -> state = State.OscEsc; else -> Unit }
            State.OscEsc -> state = State.Normal
            State.Csi -> {
                if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '!' || c == ' ' || c == '"' || c == '\'' || c == ':') {
                    params.append(c)
                } else {
                    state = State.Normal
                    csi(c, params.toString())
                }
            }
        }
    }

    private fun put(c: Char) {
        if (wrapPending && autoWrap) { cursorX = 0; lineFeed() }
        wrapPending = false
        screen[cursorY][cursorX] = Cell(c, style)
        if (cursorX == cols - 1) wrapPending = true else cursorX++
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == bottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == top) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(n: Int) {
        val s = screen
        repeat(n) {
            if (top == 0 && alt == null) pushScrollback(s[0].copyOf())
            for (y in top until bottom) s[y] = s[y + 1]
            s[bottom] = blankLine(style)
        }
    }

    private fun scrollDown(n: Int) {
        val s = screen
        repeat(n) {
            for (y in bottom downTo top + 1) s[y] = s[y - 1]
            s[top] = blankLine(style)
        }
    }

    private fun saveCursor() { savedX = cursorX; savedY = cursorY; savedStyle = style }
    private fun restoreCursor() { cursorX = savedX.coerceIn(0, cols - 1); cursorY = savedY.coerceIn(0, rows - 1); style = savedStyle; wrapPending = false }

    private fun csi(final: Char, raw: String) {
        val private = raw.startsWith("?")
        val nums = raw.trimStart('?', '>', '!').split(';').map { it.toIntOrNull() }
        fun p(i: Int, def: Int = 1) = nums.getOrNull(i)?.takeIf { it != 0 } ?: def
        fun p0(i: Int) = nums.getOrNull(i) ?: 0
        wrapPending = false
        when (final) {
            'A' -> cursorY = (cursorY - p(0)).coerceAtLeast(if (cursorY >= top) top else 0)
            'B', 'e' -> cursorY = (cursorY + p(0)).coerceAtMost(if (cursorY <= bottom) bottom else rows - 1)
            'C', 'a' -> cursorX = (cursorX + p(0)).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - p(0)).coerceAtLeast(0)
            'E' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); cursorX = 0 }
            'G', '`' -> cursorX = (p(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorY = (p(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { cursorY = (p(0) - 1).coerceIn(0, rows - 1); cursorX = (p(1) - 1).coerceIn(0, cols - 1) }
            'J' -> eraseDisplay(p0(0))
            'K' -> eraseLine(p0(0))
            'X' -> { val n = p(0); for (x in cursorX until minOf(cols, cursorX + n)) screen[cursorY][x] = Cell(' ', Style(bg = style.bg)) }
            'P' -> { val n = p(0); val line = screen[cursorY]; for (x in cursorX until cols) line[x] = if (x + n < cols) line[x + n] else Cell(' ', Style(bg = style.bg)) }
            '@' -> { val n = p(0); val line = screen[cursorY]; for (x in cols - 1 downTo cursorX) line[x] = if (x - n >= cursorX) line[x - n] else Cell() }
            'L' -> if (cursorY in top..bottom) { val s = screen; repeat(p(0)) { for (y in bottom downTo cursorY + 1) s[y] = s[y - 1]; s[cursorY] = blankLine(style) } }
            'M' -> if (cursorY in top..bottom) { val s = screen; repeat(p(0)) { for (y in cursorY until bottom) s[y] = s[y + 1]; s[bottom] = blankLine(style) } }
            'S' -> scrollUp(p(0))
            'T' -> if (!private) scrollDown(p(0))
            'r' -> { top = (p(0) - 1).coerceIn(0, rows - 1); bottom = (nums.getOrNull(1)?.takeIf { it > 0 } ?: rows).minus(1).coerceIn(top, rows - 1); cursorX = 0; cursorY = 0 }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'm' -> sgr(if (raw.isEmpty()) listOf(0) else nums.map { it ?: 0 })
            'h', 'l' -> if (private) {
                val on = final == 'h'
                for (n in nums) when (n) {
                    25 -> cursorVisible = on
                    7 -> autoWrap = on
                    47, 1047, 1049 -> {
                        if (on && alt == null) { if (n == 1049) saveCursor(); alt = blank(rows); eraseDisplay(2) }
                        else if (!on && alt != null) { alt = null; if (n == 1049) restoreCursor() }
                    }
                }
            }
            else -> Unit // device status reports, mouse modes, etc. are ignored
        }
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        val bl = Cell(' ', Style(bg = style.bg))
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) s[y] = Array(cols) { bl } }
            1 -> { eraseLine(1); for (y in 0 until cursorY) s[y] = Array(cols) { bl } }
            2 -> for (y in 0 until rows) s[y] = Array(cols) { bl }
            3 -> scrollback.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY]
        val bl = Cell(' ', Style(bg = style.bg))
        val range = when (mode) { 0 -> cursorX until cols; 1 -> 0..cursorX; else -> 0 until cols }
        for (x in range) line[x] = bl
    }

    private fun sgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> style = DEFAULT
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(fg = c - 30)
                39 -> style = style.copy(fg = -1)
                in 40..47 -> style = style.copy(bg = c - 40)
                49 -> style = style.copy(bg = -1)
                in 90..97 -> style = style.copy(fg = c - 90 + 8)
                in 100..107 -> style = style.copy(bg = c - 100 + 8)
                38, 48 -> {
                    val mode = codes.getOrNull(i + 1)
                    val color = when (mode) {
                        5 -> codes.getOrNull(i + 2)?.also { i += 2 }
                        2 -> { val r = codes.getOrNull(i + 2) ?: 0; val g = codes.getOrNull(i + 3) ?: 0; val b = codes.getOrNull(i + 4) ?: 0; i += 4; 0x1000000 or (r shl 16) or (g shl 8) or b }
                        else -> null
                    }
                    if (color != null) style = if (c == 38) style.copy(fg = color) else style.copy(bg = color)
                }
            }
            i++
        }
    }

    /** Visible lines, optionally preceded by [history] scrollback lines (main screen only). */
    fun lines(history: Int = 0): List<Array<Cell>> {
        val hist = if (alt == null && history > 0) scrollback.toList().takeLast(history) else emptyList()
        return hist + screen.toList()
    }

    val scrollbackSize get() = scrollback.size

    fun text(): String = lines(scrollback.size).joinToString("\n") { l -> l.map { it.ch }.joinToString("").trimEnd() }.trimEnd()

    companion object {
        val DEFAULT = Style()

        /** xterm 256-color palette → ARGB (the first 16 are overridden by the UI theme). */
        fun color256(i: Int): Long {
            if (i >= 0x1000000) return 0xFF000000 or (i and 0xFFFFFF).toLong()
            if (i < 16) return BASE16[i]
            if (i < 232) {
                val n = i - 16
                val levels = intArrayOf(0, 95, 135, 175, 215, 255)
                return 0xFF000000 or (levels[n / 36].toLong() shl 16) or (levels[(n / 6) % 6].toLong() shl 8) or levels[n % 6].toLong()
            }
            val v = (8 + (i - 232) * 10).toLong()
            return 0xFF000000 or (v shl 16) or (v shl 8) or v
        }

        val BASE16 = longArrayOf(
            0xFF1E1E1E, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
            0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF,
        )
    }
}
