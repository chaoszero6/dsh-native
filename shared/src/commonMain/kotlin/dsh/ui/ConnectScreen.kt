package dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dsh.transport.Environment
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(app: AppState) {
    val envs by app.store.environments.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { app.connect(name, url, token, editingId) } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("DSH", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Native client for DeepSeek Harness", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (envs.isNotEmpty()) {
                Text("Saved environments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    envs.sortedByDescending { it.lastUsedAt }.forEachIndexed { i, env ->
                        if (i > 0) HorizontalDivider()
                        EnvRow(env,
                            onOpen = {
                                if (env.cookie != null) app.activate(env)
                                else { editingId = env.id; name = env.name; url = env.baseUrl; error = "Enter a fresh token for ${env.name}" }
                            },
                            onEdit = { editingId = env.id; name = env.name; url = env.baseUrl; token = "" },
                            onDelete = { app.forget(env.id) })
                    }
                }
            }

            Text(if (editingId != null) "Update environment" else "Add environment", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Environment URL") },
                placeholder = { Text("https://host.example/dsh/") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Paste the DSH address. A full URL with ?token=… fills the token too.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton({ showToken = !showToken }) { Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show token") }
                },
                supportingText = { Text("Printed by `dsh web` (changes on each DSH restart). Only a session cookie is stored.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(::submit, enabled = !busy && url.isNotBlank()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
                }
                if (editingId != null) TextButton({ editingId = null; name = ""; url = ""; token = "" }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun EnvRow(env: Environment, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(env.name, style = MaterialTheme.typography.titleSmall)
            Text(env.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (env.cookie == null) Text("Token required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onEdit) { Text("Edit") }
        IconButton(onDelete) { Icon(Icons.Default.Delete, "Forget") }
    }
}

/** Shown when the server rejects the stored cookie (e.g. revoked); asks for a fresh token in place. */
@Composable
fun ReauthCard(app: AppState, modifier: Modifier = Modifier) {
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Card(modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Authentication required", style = MaterialTheme.typography.titleMedium)
            Text("The server no longer accepts this device's session. Paste the current DSH token (or full URL).", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Token") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true; error = null
                    scope.launch { try { app.reauthenticate(token) } catch (e: Exception) { error = e.message } finally { busy = false } }
                }, enabled = token.isNotBlank() && !busy) { Text("Reconnect") }
                TextButton({ app.disconnect() }) { Text("Switch environment") }
            }
        }
    }
}
