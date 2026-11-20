package dsh.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Connection lifecycle as seen by the UI. */
sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data class Connected(val clientId: String, val home: String) : ConnState
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String?) : ConnState
    data object AuthRequired : ConnState
}

/** One-way server notification (`emit` on `$events`). */
data class ServerEvent(val event: String, val args: JsonArray)

/** Server request awaiting this client's answer (`waterfall` on `$events`): approvals and user questions. */
data class Waterfall(
    val event: String,
    val eventId: String,
    val agentId: String,
    val request: JsonObject,
    val clientId: String,
)

/** Abstraction over the WebSocket so the mux can be unit-tested without a network. */
interface MuxSocket {
    suspend fun send(text: String)
}

/**
 * Multiplexes logical streams over `api/remote.mux` (packages/api/gateway/src/stream-protocol.ts).
 * All state lives in one actor coroutine; the socket loop and collectors only post commands.
 */
class StreamMux(
    private val scope: CoroutineScope,
    private val json: kotlinx.serialization.json.Json = DshJson,
) {
    private sealed interface Cmd {
        class Open(val sub: Sub) : Cmd
        class Close(val id: String) : Cmd
        class SocketUp(val socket: MuxSocket) : Cmd
        class Incoming(val text: String) : Cmd
        data object SocketDown : Cmd
    }

    private class Sub(val id: String, val endpoint: String, val args: JsonObject, val out: SendChannel<JsonElement>) {
        var opened = false
    }

    private val cmds = Channel<Cmd>(Channel.UNLIMITED)
    private val subs = LinkedHashMap<String, Sub>()
    private var socket: MuxSocket? = null
    private var eventsStreamId: String? = null
    private var ready = false

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _waterfalls = MutableStateFlow<List<Waterfall>>(emptyList())
    val waterfalls: StateFlow<List<Waterfall>> = _waterfalls.asStateFlow()

    /** Emitted every time a new connection generation becomes ready (initial connect and every reconnect). */
    private val _generations = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val generations: SharedFlow<String> = _generations.asSharedFlow()

    private val actor: Job = scope.launch { for (c in cmds) handle(c) }

    internal fun setState(s: ConnState) { _state.value = s }

    /** Opens a logical stream; cancelling the collector sends `cancel` upstream. */
    fun open(endpoint: String, args: JsonObject): Flow<JsonElement> = callbackFlow {
        val id = newId()
        cmds.send(Cmd.Open(Sub(id, endpoint, args, channel)))
        awaitClose { cmds.trySend(Cmd.Close(id)) }
    }.buffer(Channel.UNLIMITED)

    fun removeWaterfall(eventId: String) {
        _waterfalls.value = _waterfalls.value.filterNot { it.eventId == eventId }
    }

    // ---- called by the socket loop ----
    suspend fun socketUp(socket: MuxSocket) = cmds.send(Cmd.SocketUp(socket))
    suspend fun incoming(text: String) = cmds.send(Cmd.Incoming(text))
    suspend fun socketDown() = cmds.send(Cmd.SocketDown)

    private fun frame(type: String, streamId: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("type", type); put("streamId", streamId); block() }.toString()

    private suspend fun sendOpen(sub: Sub) {
        val s = socket ?: return
        sub.opened = true
        s.send(frame("open", sub.id) {
            put("endpoint", sub.endpoint)
            put("payload", buildJsonObject { put("args", sub.args) })
        })
    }

    private suspend fun handle(c: Cmd) {
        when (c) {
            is Cmd.Open -> {
                subs[c.sub.id] = c.sub
                if (ready) runCatching { sendOpen(c.sub) }
            }
            is Cmd.Close -> {
                val sub = subs.remove(c.id) ?: return
                if (ready && sub.opened) runCatching { socket?.send(frame("cancel", sub.id)) }
            }
            is Cmd.SocketUp -> {
                socket = c.socket
                ready = false
                val ev = newId()
                eventsStreamId = ev
                c.socket.send(frame("open", ev) {
                    put("endpoint", "\$events")
                    put("payload", buildJsonObject { put("args", JsonObject(emptyMap())) })
                })
            }
            Cmd.SocketDown -> {
                socket = null
                ready = false
                eventsStreamId = null
                subs.values.forEach { it.opened = false }
                // Pending waterfalls belong to the old clientId; the server drops those deliveries.
                _waterfalls.value = emptyList()
            }
            is Cmd.Incoming -> onFrame(c.text)
        }
    }

    private suspend fun onFrame(text: String) {
        val f = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val streamId = f["streamId"].str ?: return
        if (streamId == eventsStreamId) return onEventsFrame(f)
        val sub = subs[streamId] ?: return
        when (f["type"].str) {
            "item" -> sub.out.trySend(f["value"] ?: JsonNull)
            "end" -> { subs.remove(streamId); sub.out.close() }
            "error" -> {
                subs.remove(streamId)
                val e = f["error"]
                sub.out.close(DshException(e["code"].str ?: "stream/error", e["message"].str ?: "Stream ${sub.endpoint} failed", e["details"]))
            }
        }
    }

    private suspend fun onEventsFrame(f: JsonObject) {
        when (f["type"].str) {
            "item" -> {
                val v = f["value"]
                when (v["type"].str) {
                    "ready" -> {
                        val clientId = v["clientId"].str ?: return
                        ready = true
                        _state.value = ConnState.Connected(clientId, v["host"]["home"].str ?: "")
                        for (sub in subs.values.toList()) runCatching { sendOpen(sub) }
                        _generations.emit(clientId)
                    }
                    "emit" -> _events.emit(ServerEvent(v["event"].str ?: return, v["args"].arr ?: JsonArray(emptyList())))
                    "waterfall" -> {
                        val st = _state.value as? ConnState.Connected ?: return
                        val w = Waterfall(
                            event = v["event"].str ?: return,
                            eventId = v["eventId"].str ?: return,
                            agentId = v["agentId"].str ?: "",
                            request = v["request"].obj ?: JsonObject(emptyMap()),
                            clientId = st.clientId,
                        )
                        _waterfalls.value = _waterfalls.value.filterNot { it.eventId == w.eventId } + w
                    }
                    "cancel" -> removeWaterfall(v["eventId"].str ?: return)
                }
            }
            "end", "error" -> {
                // The events stream ending means this connection generation is over.
                ready = false
            }
        }
    }

    fun close() {
        actor.cancel()
        cmds.close()
    }

    companion object {
        /** 0.5,1,2,4,8,10,10… seconds with 50–100% jitter (packages/client/connection/src/recovery-config.ts). */
        fun backoffMs(attempt: Int, random: Random = Random): Long {
            val caps = longArrayOf(500, 1000, 2000, 4000, 8000, 10000)
            val cap = caps[(attempt - 1).coerceIn(0, caps.size - 1)]
            return (cap * (0.5 + random.nextDouble() * 0.5)).toLong()
        }
    }
}

/** Waits until connected (or gives up). */
suspend fun StreamMux.awaitConnected(timeoutMs: Long = 15_000): ConnState.Connected? =
    withTimeoutOrNull(timeoutMs) {
        var r: ConnState.Connected? = null
        while (isActive && r == null) {
            r = state.value as? ConnState.Connected
            if (r == null) delay(50)
        }
        r
    }
