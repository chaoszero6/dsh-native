package dsh.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dsh.platform.PlatformServices
import kotlin.time.Clock

val LocalPlatform = compositionLocalOf<PlatformServices> { error("PlatformServices not provided") }

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val d = (nowMs() - ms) / 1000
    return when {
        d < 45 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86400 -> "${d / 3600}h"
        d < 86400 * 7 -> "${d / 86400}d"
        else -> "${d / (86400 * 7)}w"
    }
}

fun shortPath(path: String?, home: String): String {
    if (path == null) return ""
    return if (home.isNotEmpty() && path.startsWith(home)) "~" + path.removePrefix(home) else path
}

fun formatTokens(n: Long?): String = when {
    n == null -> "–"
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1_000 -> "${(n / 100) / 10.0}k"
    else -> n.toString()
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition()
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
        a
    } else 1f
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** Monospace block with horizontal scroll and selection. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color = Color.Unspecified) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(LocalExtraColors.current.codeBg).padding(10.dp)) {
        SelectionContainer {
            Text(
                text, style = MonoStyle, maxLines = maxLines, softWrap = false,
                color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Strips ANSI escape sequences from terminal output. */
fun stripAnsi(s: String): String = s.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]"), "")
