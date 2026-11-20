package dsh.ui

import androidx.compose.runtime.Composable
import dsh.data.DshClient

// Phase 2/3 panels. Replaced by full implementations in later phases.
private const val SOON = "Coming in the next build"

@Composable fun FilesPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Files", onClose, docked) { Placeholder(SOON) }
@Composable fun TerminalPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Terminal", onClose, docked) { Placeholder(SOON) }
@Composable fun ChangesPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Changes", onClose, docked) { Placeholder(SOON) }
@Composable fun PlanPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Plan", onClose, docked) { Placeholder(SOON) }
@Composable fun GoalPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Goal", onClose, docked) { Placeholder(SOON) }
@Composable fun JobsPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Background jobs", onClose, docked) { Placeholder(SOON) }
@Composable fun SubagentsPanel(client: DshClient, nav: MainNav, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Subagents", onClose, docked) { Placeholder(SOON) }
@Composable fun TrajectoryPanel(client: DshClient, sessionId: String, docked: Boolean, onClose: () -> Unit) = PanelScaffold("Trajectory", onClose, docked) { Placeholder(SOON) }
@Composable fun SchedulesScreen(client: DshClient, onBack: () -> Unit) = PanelScaffold("Scheduled tasks", onBack, docked = false) { Placeholder(SOON) }
@Composable fun PluginsScreen(client: DshClient, onBack: () -> Unit) = PanelScaffold("Plugins", onBack, docked = false) { Placeholder(SOON) }
