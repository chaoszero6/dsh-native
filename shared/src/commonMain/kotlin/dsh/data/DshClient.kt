package dsh.data

import dsh.gen.AgentPresetRoster
import dsh.gen.CommandsListResultItem
import dsh.gen.ModelCatalog
import dsh.gen.PermissionCatalog
import dsh.gen.SessionCreateRequest
import dsh.gen.SessionForkRequest
import dsh.gen.SessionListRequest
import dsh.gen.SessionRenameRequest
import dsh.gen.SessionSelectModelRequest
import dsh.gen.WorkspaceArchiveSessionRequest
import dsh.gen.WorkspaceCreateRequest
import dsh.gen.WorkspaceDeleteRequest
import dsh.gen.WorkspaceFollowFrame
import dsh.gen.WorkspacePinSessionRequest
import dsh.gen.WorkspaceRenameRequest
import dsh.gen.WorkspaceUnarchiveSessionRequest
import dsh.gen.WorkspaceUnpinSessionRequest
import dsh.model.SessionProjections
import dsh.transport.AuthRequiredException
import dsh.transport.ConnState
import dsh.transport.DshConnection
import dsh.transport.DshJson
import dsh.transport.Waterfall
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.obj
import dsh.transport.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class SessionSummary(
    val id: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String?,
    val parentSessionId: String?,
    val origin: String?,
    val agentAvailable: Boolean,
) {
    val isSubagent: Boolean get() = origin == "subagent" || parentSessionId != null
}

data class WorkspaceInfo(val id: String, val path: String, val title: String, val sessionIds: List<String>)

/** User-visible notice (errors, info) surfaced as a snackbar. */
data class UiNotice(val message: String, val isError: Boolean = true)

/**
 * Application-level state for one connected environment: sessions, workspaces, live status, catalogs,
 * and the pending approval/question inbox. UI screens observe the StateFlows.
 */
class DshClient(val conn: DshConnection) {
    val scope: CoroutineScope get() = conn.scope
    val api get() = conn.api

    private val _sessions = MutableStateFlow<Map<String, SessionSummary>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionSummary>> = _sessions.asStateFlow()

    private val _projections = MutableStateFlow<Map<String, SessionProjections>>(emptyMap())
    val projections: StateFlow<Map<String, SessionProjections>> = _projections.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceInfo>> = _workspaces.asStateFlow()

    private val _archived = MutableStateFlow<Set<String>>(emptySet())
    val archived: StateFlow<Set<String>> = _archived.asStateFlow()

    private val _pinned = MutableStateFlow<List<String>>(emptyList())
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    private val _notices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 32)
    val notices: SharedFlow<UiNotice> = _notices.asSharedFlow()

    private val _home = MutableStateFlow("")
    val home: StateFlow<String> = _home.asStateFlow()

    /** Incremented when catalogs (models, commands, presets, settings) may have changed server-side. */
    private val _catalogVersion = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> = _catalogVersion.asStateFlow()

    val state: StateFlow<ConnState> get() = conn.state
    val waterfalls: StateFlow<List<Waterfall>> get() = conn.waterfalls
    val events get() = conn.events

    private val conversations = LinkedHashMap<String, ConversationController>()

    fun notice(msg: String, isError: Boolean = true) { _notices.tryEmit(UiNotice(msg, isError)) }

    fun start() {
        conn.start()
        scope.launch {
            conn.generations.collect {
                (conn.state.value as? ConnState.Connected)?.let { c -> _home.value = c.home }
                refreshSessions()
                _catalogVersion.update { it + 1 }
            }
        }
        scope.launch { followForever("session/control") { onControl(it) } }
        scope.launch { followForever("workspace/follow") { onWorkspaceFrame(it) } }
        scope.launch {
            conn.events.collect { ev ->
                val a = ev.args
                when (ev.event) {
                    "api-session/added" -> a.getOrNull(0)?.let { s -> parseSummary(s)?.let { put(it) } }
                    "api-session/removed" -> a.getOrNull(0).str?.let { id -> _sessions.update { it - id } }
                    "api-session/status" -> a.getOrNull(0).str?.let { id ->
                        val running = a.getOrNull(1).bool ?: false
                        _sessions.update { m -> m[id]?.let { m + (id to it.copy(running = running)) } ?: m }
                        if (m(id) == null) refreshSessionsLater()
                    }
                    "api-session/activity" -> a.getOrNull(0).str?.let { id ->
                        val at = a.getOrNull(1).long ?: 0
                        _sessions.update { m -> m[id]?.let { m + (id to it.copy(updatedAt = at)) } ?: m }
                    }
                    "api-session/error" -> notice(a.getOrNull(1).str ?: "Session error")
                    "llm/adapters-updated", "credentials/record-updated", "credentials/reference-updated",
                    "commands/change", "permission-presets/catalog-changed", "settings/document-updated",
                    "agent-preset/selected", "plugin-manager/changed" -> _catalogVersion.update { it + 1 }
                    "deepseek-account/session-expired" -> notice("DeepSeek account session expired — sign in again in Settings › Account")
                    "deepseek-account/model-sign-in-required" -> notice("This model requires signing in to a DeepSeek account (Settings › Account)")
                }
            }
        }
    }

    private fun m(id: String) = _sessions.value[id]

    private fun put(s: SessionSummary) = _sessions.update { it + (s.id to s) }

    /** Collects a stream for the lifetime of the client, resubscribing after errors. */
    private suspend fun followForever(endpoint: String, args: JsonObject = JsonObject(emptyMap()), onItem: suspend (JsonElement) -> Unit) {
        var failures = 0
        while (true) {
            try {
                conn.streamRaw(endpoint, args).collect { failures = 0; onItem(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
            }
            delay((1000L * failures).coerceAtMost(15_000))
        }
    }

    private fun onControl(f: JsonElement) {
        when (f["type"].str) {
            "baseline" -> {
                val map = f["value"]["projections"].obj ?: return
                _projections.update { cur -> cur + map.mapValues { (_, v) -> SessionProjections.parse(v) } }
            }
            "projection" -> {
                val id = f["sessionId"].str ?: return
                val key = f["key"].str ?: return
                val seq = f["seq"].long ?: 0
                _projections.update { cur -> cur + (id to (cur[id] ?: SessionProjections()).with(key, f["value"], seq)) }
            }
        }
    }

    /** Merge projections from a follow snapshot (authoritative for that session at that seq). */
    fun mergeProjections(sessionId: String, p: SessionProjections) {
        _projections.update { cur ->
            val existing = cur[sessionId]
            if (existing == null || existing.asOfSeq <= p.asOfSeq) cur + (sessionId to p) else cur
        }
    }

    private fun onWorkspaceFrame(f: JsonElement) {
        when (val frame = runCatching { DshJson.decodeFromJsonElement(WorkspaceFollowFrame.serializer(), f) }.getOrNull()) {
            is WorkspaceFollowFrame.Baseline -> {
                val v = f["value"]
                _workspaces.value = frame.value.items.map { WorkspaceInfo(it.workspaceId, it.path, it.title, it.sessionIds) }
                v["archivedSessionIds"].let { a -> (a as? kotlinx.serialization.json.JsonArray)?.let { arr -> _archived.value = arr.mapNotNull { it.str }.toSet() } }
                v["pinnedSessionIds"].let { a -> (a as? kotlinx.serialization.json.JsonArray)?.let { arr -> _pinned.value = arr.mapNotNull { it.str } } }
            }
            is WorkspaceFollowFrame.Upsert -> {
                val w = frame.workspace
                val info = WorkspaceInfo(w.workspaceId, w.path, w.title, w.sessionIds)
                _workspaces.update { list -> if (list.any { it.id == info.id }) list.map { if (it.id == info.id) info else it } else list + info }
            }
            is WorkspaceFollowFrame.Remove -> _workspaces.update { list -> list.filterNot { it.id == frame.workspaceId } }
            is WorkspaceFollowFrame.Order -> _workspaces.update { list -> frame.workspaceIds.mapNotNull { id -> list.firstOrNull { it.id == id } } + list.filterNot { it.id in frame.workspaceIds } }
            is WorkspaceFollowFrame.Archived -> _archived.value = frame.archivedSessionIds.toSet()
            is WorkspaceFollowFrame.Pinned -> _pinned.value = frame.pinnedSessionIds
            else -> Unit
        }
    }

    private fun parseSummary(s: JsonElement?): SessionSummary? {
        val id = s["sessionId"].str ?: return null
        s["projections"]?.let { p -> if (p is JsonObject) mergeProjections(id, SessionProjections.parse(p)) }
        return SessionSummary(
            id = id,
            updatedAt = s["updatedAt"].long ?: 0,
            running = s["running"].bool ?: false,
            blank = s["blank"].bool ?: false,
            cwd = s["cwd"].str,
            parentSessionId = s["parentSessionId"].str,
            origin = s["origin"].str,
            agentAvailable = s["agentAvailable"].bool ?: true,
        )
    }

    private var refreshPending = false
    private fun refreshSessionsLater() {
        if (refreshPending) return
        refreshPending = true
        scope.launch { delay(300); refreshPending = false; refreshSessions() }
    }

    suspend fun refreshSessions() {
        runCatching {
            val all = mutableMapOf<String, SessionSummary>()
            var cursor: String? = null
            do {
                val page = conn.callRaw("session/list", kotlinx.serialization.json.buildJsonObject {
                    put("_request", DshJson.encodeToJsonElement(SessionListRequest.serializer(), SessionListRequest(cursor)))
                })
                (page["items"] as? kotlinx.serialization.json.JsonArray)?.forEach { parseSummary(it)?.let { s -> all[s.id] = s } }
                cursor = page["nextCursor"].str ?: page["cursor"].str
            } while (cursor != null && all.size < 5000)
            _sessions.value = all
        }.onFailure { if (it !is AuthRequiredException && it !is CancellationException) notice("Couldn't load sessions: ${it.message}") }
    }

    fun conversation(sessionId: String): ConversationController =
        conversations.getOrPut(sessionId) { ConversationController(this, sessionId) }.also {
            // Keep a small LRU of live follow subscriptions.
            conversations.remove(sessionId); conversations[sessionId] = it
            while (conversations.size > 6) {
                val oldest = conversations.keys.first()
                conversations.remove(oldest)?.stop()
            }
        }

    // ---------- actions ----------
    suspend fun <T> guard(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        notice("$what failed: ${e.message ?: e::class.simpleName}")
        null
    }

    suspend fun createSession(workspaceId: String? = null, cwd: String? = null, agentPreset: String? = null): String? =
        guard("Create session") { api.session.create(SessionCreateRequest(workspaceId = workspaceId, cwd = cwd, agentPreset = agentPreset)).sessionId }
            ?.also { refreshSessionsLater() }

    suspend fun renameSession(id: String, title: String) = guard("Rename") { api.session.rename(SessionRenameRequest(id, title)) }
    suspend fun forkSession(id: String, atSeq: Long? = null): String? =
        guard("Fork") { conn.callRaw("session/fork", kotlinx.serialization.json.buildJsonObject { put("request", DshJson.encodeToJsonElement(SessionForkRequest.serializer(), SessionForkRequest(id, atSeq?.toDouble()))) }) }
            ?.let { it["sessionId"].str }.also { refreshSessionsLater() }
    suspend fun archive(id: String) = guard("Archive") { api.workspace.archiveSession(WorkspaceArchiveSessionRequest(id)) }
    suspend fun unarchive(id: String) = guard("Unarchive") { api.workspace.unarchiveSession(WorkspaceUnarchiveSessionRequest(id)) }
    suspend fun pin(id: String) = guard("Pin") { api.workspace.pinSession(WorkspacePinSessionRequest(id)) }
    suspend fun unpin(id: String) = guard("Unpin") { api.workspace.unpinSession(WorkspaceUnpinSessionRequest(id)) }
    suspend fun createWorkspace(path: String) = guard("Create workspace") { api.workspace.create(WorkspaceCreateRequest(path)) }
    suspend fun renameWorkspace(id: String, title: String) = guard("Rename workspace") { api.workspace.rename(WorkspaceRenameRequest(id, title)) }
    suspend fun deleteWorkspace(id: String) = guard("Delete workspace") { api.workspace.delete(WorkspaceDeleteRequest(id)) }

    suspend fun selectModel(sessionId: String, provider: String, model: String, effort: String?) =
        guard("Select model") { api.session.selectModel(SessionSelectModelRequest(sessionId, provider, model, effort)) }

    // ---------- catalogs (cached per catalogVersion) ----------
    private val cache = HashMap<String, Pair<Int, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, load: suspend () -> T): T? {
        val v = _catalogVersion.value
        cache[key]?.let { (ver, value) -> if (ver == v) return value as T }
        val value = guard("Load $key") { load() } ?: return null
        cache[key] = v to value
        return value
    }

    suspend fun modelCatalog(): ModelCatalog? = cached("models") { api.session.modelCatalog() }
    suspend fun permissionCatalog(): PermissionCatalog? = cached("permissions") { api.permissionPresets.catalog() }
    suspend fun agentPresets(): AgentPresetRoster? = cached("agentPresets") { api.agentPresets.list() }
    suspend fun commands(sessionId: String): List<CommandsListResultItem>? = cached("commands:$sessionId") { api.commands.list(sessionId) }
    fun invalidateCatalogs() = _catalogVersion.update { it + 1 }

    fun close() {
        conversations.values.forEach { it.stop() }
        conn.close()
    }
}

/** Convenience: build a flow-safe display title for a session. */
fun SessionProjections?.displayTitle(fallback: String = "New session"): String = this?.title?.takeIf { it.isNotBlank() } ?: fallback

internal fun <T> Flow<T>.ignore() = Unit
