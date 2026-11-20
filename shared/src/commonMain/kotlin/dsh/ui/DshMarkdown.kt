package dsh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/** Markdown tuned for chat: compact headings, monospace code. */
@Composable
fun DshMarkdown(text: String, modifier: Modifier = Modifier) {
    val t = MaterialTheme.typography
    Markdown(
        content = text,
        modifier = modifier,
        typography = markdownTypography(
            h1 = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            h2 = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            h3 = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            h4 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            h5 = t.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            h6 = t.bodyMedium.copy(fontWeight = FontWeight.Medium),
            text = t.bodyLarge,
            paragraph = t.bodyLarge,
            code = MonoStyle,
            inlineCode = MonoStyle.copy(fontSize = t.bodyMedium.fontSize),
        ),
    )
}
