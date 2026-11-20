package dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.data.displayTitle
import dsh.transport.ConnState
import dsh.transport.get
import dsh.transport.str

/** Which panel occupies the right dock (wide) or a full-screen detail (compact). */
enum class SidePanel { Files, Terminal, Changes, Plan, Jobs, Goal, Subagents, Trajectory, Schedules, Settings }

class MainNav {
    var selected by mutableStateOf<String?>(null)
    var panel by mutableStateOf<SidePanel?>(null)
    /** Path opened in the document preview (Files panel). */
    var previewPath by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var showSchedules by mutableStateOf(false)
    var showPlugins by mutableStateOf(false)
    /** Subagent child being viewed (parentSessionId to childSessionId). */
    var subagent by mutableStateOf<Pair<String, String>?>(null)
}

@Composable
fun MainScreen(app: AppState, client: DshClient) {
    val nav = remember(client) { MainNav() }
    val conn by client.state.collectAsState()
    val waterfalls by client.waterfalls.collectAsState()
    val sessions by client.sessions.collectAsState()
    val projections by client.projections.collectAsState()
    val pending by app.pendingOpenSession.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val platform = LocalPlatform.current

    LaunchedEffect(client) { client.notices.collect { snackbar.showSnackbar(it.message) } }
    LaunchedEffect(pending, sessions.isNotEmpty()) {
        pending?.let { if (sessions.containsKey(it)) { nav.selected = it; app.pendingOpenSession.value = null } }
    }
    // Background notifications: approvals/questions and finished turns.
    val notified = remember { HashSet<String>() }
    LaunchedEffect(waterfalls) {
        for (w in waterfalls) if (notified.add(w.eventId)) {
            val title = projections[w.agentId].displayTitle("Session")
            val what = if (w.event == "approval/request") "Approval needed: ${w.request["toolName"].str ?: "tool"}" else "The agent has a question"
            platform.notify("wf-${w.eventId}", title, what)
        }
        val live = waterfalls.map { it.eventId }.toSet()
        notified.filter { it !in live }.forEach { platform.cancelNotification("wf-$it") }
        notified.retainAll(live)
    }
    val wasRunning = remember { HashMap<String, Boolean>() }
    LaunchedEffect(sessions) {
        val running = sessions.values.filter { it.running }
        platform.setBackgroundActivity(running.isNotEmpty() || waterfalls.isNotEmpty(),
            if (running.isEmpty()) "Waiting for your input" else "${running.size} session(s) running")
        for (s in sessions.values) {
            if (wasRunning[s.id] == true && !s.running && !s.isSubagent && app.store.prefs.value.notifications) {
                platform.notify("done-${s.id}", projections[s.id].displayTitle(), "Agent finished")
            }
            wasRunning[s.id] = s.running
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val width = maxWidth
        val wide = width >= 840.dp
        Column(Modifier.fillMaxSize()) {
            ConnectionBanner(conn, client)
            val otherPending = waterfalls.filter { it.agentId != nav.selected }
            if (otherPending.isNotEmpty()) {
                val w = otherPending.first()
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)
                        .clickable { nav.selected = w.agentId; nav.subagent = null }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.NotificationsActive, null)
                    Text(
                        (if (otherPending.size > 1) "${otherPending.size} requests waiting · " else "") +
                            "${if (w.event == "approval/request") "Approval" else "Question"} in “${projections[w.agentId].displayTitle("a session")}” — tap to open",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Sidebar(app, client, nav, compact = false)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) { CenterPane(app, client, nav, wide = true) }
                        val panel = nav.panel
                        if (panel != null && nav.selected != null && width >= 1100.dp) {
                            VerticalDivider()
                            Box(Modifier.width(if (panel == SidePanel.Terminal || nav.previewPath != null) 520.dp else 400.dp).fillMaxHeight()) {
                                SidePanelHost(client, nav, panel, nav.selected!!, docked = true)
                            }
                        }
                    }
                } else {
                    val sel = nav.selected
                    when {
                        nav.showSettings -> SettingsScreen(app, client, onBack = { nav.showSettings = false })
                        nav.showSchedules -> SchedulesScreen(client, onBack = { nav.showSchedules = false })
                        nav.showPlugins -> PluginsScreen(client, onBack = { nav.showPlugins = false })
                        sel == null -> Sidebar(app, client, nav, compact = true)
                        nav.panel != null -> SidePanelHost(client, nav, nav.panel!!, sel, docked = false)
                        else -> CenterPane(app, client, nav, wide = false)
                    }
                    PlatformBackHandler(sel != null || nav.showSettings || nav.showSchedules || nav.showPlugins) {
                        when {
                            nav.showSettings -> nav.showSettings = false
                            nav.showSchedules -> nav.showSchedules = false
                            nav.showPlugins -> nav.showPlugins = false
                            nav.previewPath != null -> nav.previewPath = null
                            nav.panel != null -> nav.panel = null
                            nav.subagent != null -> nav.subagent = null
                            else -> nav.selected = null
                        }
                    }
                }
                if (conn is ConnState.AuthRequired) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                        ReauthCard(app, Modifier.padding(24.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

@Composable
private fun CenterPane(app: AppState, client: DshClient, nav: MainNav, wide: Boolean) {
    when {
        wide && nav.showSettings -> SettingsScreen(app, client, onBack = { nav.showSettings = false })
        wide && nav.showSchedules -> SchedulesScreen(client, onBack = { nav.showSchedules = false })
        wide && nav.showPlugins -> PluginsScreen(client, onBack = { nav.showPlugins = false })
        nav.selected == null -> EmptyState(client, nav)
        else -> {
            val sub = nav.subagent
            if (sub != null && sub.first == nav.selected) SubagentChatScreen(client, nav, sub.first, sub.second, wide)
            else ChatScreen(client, nav, nav.selected!!, wide)
        }
    }
}

@Composable
private fun ConnectionBanner(state: ConnState, client: DshClient) {
    val (text, color) = when (state) {
        is ConnState.Connected, ConnState.Idle, ConnState.AuthRequired -> return
        ConnState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.secondaryContainer
        is ConnState.Reconnecting -> "Connection lost — retrying (attempt ${state.attempt})${state.reason?.let { ": $it" } ?: ""}" to MaterialTheme.colorScheme.errorContainer
    }
    Row(
        Modifier.fillMaxWidth().background(color).clickable { client.conn.reconnectNow() }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(MaterialTheme.colorScheme.error, pulsing = true)
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        if (state is ConnState.Reconnecting) Text("Tap to retry now", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyState(client: DshClient, nav: MainNav) {
    val sessions by client.sessions.collectAsState()
    val workspaces by client.workspaces.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select a session or start a new one", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${sessions.values.count { !it.isSubagent && !it.blank }} sessions · ${workspaces.size} workspaces", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Android system back; no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
