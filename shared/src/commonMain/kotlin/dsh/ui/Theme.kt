package dsh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF4D6BFE) // DeepSeek-like accent

private val Light = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF0B1B66),
    secondary = Color(0xFF5B5F72), secondaryContainer = Color(0xFFE2E4F2),
    background = Color(0xFFFBFBFD), surface = Color(0xFFFBFBFD),
    surfaceVariant = Color(0xFFF0F1F6), surfaceContainer = Color(0xFFF3F4F8),
    surfaceContainerHigh = Color(0xFFEBEDF3), surfaceContainerLow = Color(0xFFF7F8FB),
    outline = Color(0xFFBFC3D2), outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFC62828), errorContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFF2E7D32), tertiaryContainer = Color(0xFFD7F0D9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8EA1FF), onPrimary = Color(0xFF0B1B66),
    primaryContainer = Color(0xFF26338A), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFC4C6D7), secondaryContainer = Color(0xFF3A3D4C),
    background = Color(0xFF141519), surface = Color(0xFF141519),
    surfaceVariant = Color(0xFF22242B), surfaceContainer = Color(0xFF1B1D22),
    surfaceContainerHigh = Color(0xFF25272E), surfaceContainerLow = Color(0xFF17181C),
    outline = Color(0xFF5A5E6E), outlineVariant = Color(0xFF34363F),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1A16),
    tertiary = Color(0xFF81C784), tertiaryContainer = Color(0xFF1E3B20),
)

@Immutable
data class DshExtraColors(val codeBg: Color, val diffAdd: Color, val diffDel: Color, val running: Color, val warn: Color)

val LocalExtraColors = staticCompositionLocalOf { DshExtraColors(Color.LightGray, Color.Green, Color.Red, Color.Blue, Color.Yellow) }

val Mono = FontFamily.Monospace

@Composable
fun DshTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val extra = if (dark) DshExtraColors(Color(0xFF1E2027), Color(0xFF1F3B24), Color(0xFF47201F), Color(0xFF8EA1FF), Color(0xFFFFB74D))
    else DshExtraColors(Color(0xFFF2F3F7), Color(0xFFE3F5E5), Color(0xFFFBE3E1), Blue, Color(0xFFEF6C00))
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra, content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp)
