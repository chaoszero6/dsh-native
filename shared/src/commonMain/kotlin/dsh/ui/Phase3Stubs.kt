package dsh.ui

import androidx.compose.runtime.Composable
import dsh.data.DshClient

// Phase 3 screens. Replaced by full implementations in the next phase.
private const val SOON = "Coming in the next build"

@Composable fun SchedulesScreen(client: DshClient, onBack: () -> Unit) = PanelScaffold("Scheduled tasks", onBack, docked = false) { Placeholder(SOON) }
@Composable fun PluginsScreen(client: DshClient, onBack: () -> Unit) = PanelScaffold("Plugins", onBack, docked = false) { Placeholder(SOON) }
