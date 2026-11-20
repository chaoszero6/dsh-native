package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dsh.data.DshClient
import dsh.gen.DshApi

/** App settings. Server-side settings sections are appended by [ServerSettingsSections] (Phase 3). */
@Composable
fun SettingsScreen(app: AppState, client: DshClient, onBack: () -> Unit) {
    val prefs by app.store.prefs.collectAsState()
    val env by app.activeEnv.collectAsState()
    PanelScaffold("Settings", onBack, docked = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("App")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", Modifier.weight(1f))
                    listOf("system", "light", "dark").forEach { t -> FilterChip(prefs.theme == t, { app.store.updatePrefs { it.copy(theme = t) } }, { Text(t.replaceFirstChar { c -> c.uppercase() }) }) }
                }
                SwitchRow("Notifications", "Notify when approvals are needed or an agent finishes (while the app is in the background)", prefs.notifications) { v -> app.store.updatePrefs { it.copy(notifications = v) } }
                HorizontalDivider()
                SectionTitle("Environment")
                Text(env?.name ?: "", style = MaterialTheme.typography.bodyLarge)
                Text(env?.baseUrl ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ client.conn.reconnectNow() }) { Text("Reconnect") }
                    OutlinedButton({ app.disconnect() }) { Text("Switch environment") }
                }
                ServerSettingsSections(client)
                HorizontalDivider()
                Text("Client built against DSH ${DshApi.DSH_VERSION} · ${DshApi.ENDPOINTS.size} API methods · ${LocalPlatform.current.name}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

/** Server settings (models/providers, credentials, account, plugin pages) — Phase 3. */
@Composable
fun ServerSettingsSections(client: DshClient) {}
