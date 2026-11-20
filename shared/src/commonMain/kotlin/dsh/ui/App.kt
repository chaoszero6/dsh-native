package dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import dsh.data.DshClient
import dsh.data.EnvironmentStore
import dsh.platform.PlatformServices
import dsh.transport.DshConnection
import dsh.transport.Environment
import dsh.transport.createHttpClient
import dsh.transport.exchangeToken
import dsh.transport.newId
import dsh.transport.DshException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Process-wide app state: saved environments and the active client. Owned by the platform entry point. */
class AppState(val platform: PlatformServices) {
    val store = EnvironmentStore(platform.store)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _client = MutableStateFlow<DshClient?>(null)
    val client: StateFlow<DshClient?> = _client.asStateFlow()

    private val _activeEnv = MutableStateFlow<Environment?>(null)
    val activeEnv: StateFlow<Environment?> = _activeEnv.asStateFlow()

    /** Session to open once connected (e.g. from a notification tap). */
    val pendingOpenSession = MutableStateFlow<String?>(null)

    init {
        // Auto-reconnect to the last used environment if we still hold its cookie.
        store.get(store.prefs.value.lastEnvironmentId)?.takeIf { it.cookie != null }?.let { activate(it) }
    }

    /** Exchanges [token] for a cookie and connects. Throws [DshException] with a user-readable message. */
    suspend fun connect(name: String, rawUrl: String, token: String?, existingId: String? = null): Environment {
        val (base, urlToken) = Environment.parseInput(rawUrl)
        val tok = token?.takeIf { it.isNotBlank() } ?: urlToken
            ?: throw DshException("auth/no-token", "Enter the DSH token (the value after ?token= in the DSH URL).")
        val cookie = withContext(Dispatchers.Default) {
            val http = createHttpClient()
            try { exchangeToken(http, base, tok) } finally { http.close() }
        }
        val env = Environment(
            id = existingId ?: newId(),
            name = name.ifBlank { base.removePrefix("https://").removePrefix("http://").trimEnd('/') },
            baseUrl = base, cookie = cookie, lastUsedAt = nowMs(),
        )
        store.upsert(env)
        activate(env)
        return env
    }

    fun activate(env: Environment) {
        _client.value?.close()
        val updated = env.copy(lastUsedAt = nowMs())
        store.upsert(updated)
        store.updatePrefs { it.copy(lastEnvironmentId = env.id) }
        _activeEnv.value = updated
        _client.value = DshClient(DshConnection(updated)).also { it.start() }
    }

    /** Re-authenticates the active environment with a fresh token, keeping the client and its UI state. */
    suspend fun reauthenticate(token: String) {
        val env = _activeEnv.value ?: return
        val (_, urlToken) = Environment.parseInput(token)
        val tok = urlToken ?: token.trim()
        val cookie = withContext(Dispatchers.Default) {
            val http = createHttpClient()
            try { exchangeToken(http, env.baseUrl, tok) } finally { http.close() }
        }
        val updated = env.copy(cookie = cookie)
        store.upsert(updated)
        _activeEnv.value = updated
        _client.value?.conn?.updateEnvironment(updated)
    }

    fun disconnect() {
        _client.value?.close()
        _client.value = null
        _activeEnv.value = null
        store.updatePrefs { it.copy(lastEnvironmentId = null) }
    }

    fun forget(envId: String) {
        if (_activeEnv.value?.id == envId) disconnect()
        store.remove(envId)
    }
}

@Composable
fun DshApp(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val client by app.client.collectAsState()
    DshTheme(prefs.theme) {
        CompositionLocalProvider(LocalPlatform provides app.platform) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    val c = client
                    if (c == null) ConnectScreen(app) else MainScreen(app, c)
                }
            }
        }
    }
}

/** Small holder so screens can keep transient UI state across recompositions. */
class Holder<T>(initial: T) { var value by mutableStateOf(initial) }

@Composable
fun <T> rememberHolder(initial: T) = remember { Holder(initial) }
