package dsh.data

import dsh.platform.KeyValueStore
import dsh.transport.DshJson
import dsh.transport.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable

@Serializable
data class AppPrefs(
    val lastEnvironmentId: String? = null,
    val theme: String = "system", // system | light | dark
    val showReasoning: Boolean = true,
    val enterToSend: Boolean = true,
    val fontScale: Float = 1f,
    val notifications: Boolean = true,
)

/** Persists environments (URL + auth cookie; never the launch token) and app preferences. */
class EnvironmentStore(private val kv: KeyValueStore) {
    private val _envs = MutableStateFlow(load())
    val environments: StateFlow<List<Environment>> = _envs.asStateFlow()

    private val _prefs = MutableStateFlow(
        kv.get(PREFS)?.let { runCatching { DshJson.decodeFromString(AppPrefs.serializer(), it) }.getOrNull() } ?: AppPrefs(),
    )
    val prefs: StateFlow<AppPrefs> = _prefs.asStateFlow()

    private fun load(): List<Environment> =
        kv.get(ENVS)?.let { runCatching { DshJson.decodeFromString(ListSerializer(Environment.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun save(list: List<Environment>) {
        _envs.value = list
        kv.put(ENVS, DshJson.encodeToString(ListSerializer(Environment.serializer()), list))
    }

    fun upsert(env: Environment) = save(_envs.value.filterNot { it.id == env.id } + env)
    fun remove(id: String) = save(_envs.value.filterNot { it.id == id })
    fun get(id: String?): Environment? = _envs.value.firstOrNull { it.id == id }

    fun updatePrefs(f: (AppPrefs) -> AppPrefs) {
        val p = f(_prefs.value)
        _prefs.value = p
        kv.put(PREFS, DshJson.encodeToString(AppPrefs.serializer(), p))
    }

    companion object {
        private const val ENVS = "environments.v1"
        private const val PREFS = "prefs.v1"
    }
}
