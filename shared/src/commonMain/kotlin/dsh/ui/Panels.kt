package dsh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dsh.data.DshClient

/** Title bar + body used by every side panel and full-screen page. */
@Composable
fun PanelScaffold(
    title: String,
    onClose: () -> Unit,
    docked: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!docked) IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f).padding(start = if (docked) 12.dp else 0.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            if (docked) IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
fun SidePanelHost(client: DshClient, nav: MainNav, panel: SidePanel, sessionId: String, docked: Boolean) {
    val close = { nav.panel = null; nav.previewPath = null }
    when (panel) {
        SidePanel.Files -> FilesPanel(client, nav, sessionId, docked, close)
        SidePanel.Terminal -> TerminalPanel(client, sessionId, docked, close)
        SidePanel.Changes -> ChangesPanel(client, nav, sessionId, docked, close)
        SidePanel.Plan -> PlanPanel(client, nav, sessionId, docked, close)
        SidePanel.Goal -> GoalPanel(client, sessionId, docked, close)
        SidePanel.Jobs -> JobsPanel(client, sessionId, docked, close)
        SidePanel.Subagents -> SubagentsPanel(client, nav, sessionId, docked, close)
        SidePanel.Trajectory -> TrajectoryPanel(client, sessionId, docked, close)
        else -> PanelScaffold(panel.name, close, docked) { Placeholder("Not available") }
    }
}

@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
