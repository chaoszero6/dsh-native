package dsh.transport

import dsh.gen.DshApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A live connection to one DSH environment.
 * - Unary calls: HTTP POST (independent of the socket state).
 * - Streams + server events: one multiplexed WebSocket with automatic reconnect.
 */
class DshConnection(
    initial: Environment,
    private val http: HttpClient = createHttpClient(),
    parentScope: CoroutineScope? = null,
) : DshTransport {
    val scope: CoroutineScope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val json: Json = DshJson

    @kotlin.concurrent.Volatile
    var environment: Environment = initial
        private set

    val mux = StreamMux(scope, json)
    val httpLayer = HttpLayer(http, { environment }, { mux.setState(ConnState.AuthRequired) })
    val api = DshApi(this)

    val state get() = mux.state
    val events get() = mux.events
    val waterfalls get() = mux.waterfalls
    val generations get() = mux.generations

    private var loop: Job? = null
    private val loopLock = Mutex()

    /** Starts (or restarts) the socket loop. */
    fun start() {
        scope.launch {
            loopLock.withLock {
                loop?.cancel()
                loop = scope.launch { socketLoop() }
            }
        }
    }

    /** Updates the auth cookie (after a token re-exchange) and reconnects. */
    fun updateEnvironment(env: Environment) {
        environment = env
        start()
    }

    private suspend fun socketLoop() {
        var attempt = 0
        var lastError: String? = null
        while (scope.isActive) {
            mux.setState(if (attempt == 0) ConnState.Connecting else mux.state.value)
            try {
                http.webSocket(urlString = environment.muxUrl, request = {
                    environment.cookie?.let { header(HttpHeaders.Cookie, it) }
                }) {
                    val session = this
                    mux.socketUp(object : MuxSocket {
                        override suspend fun send(text: String) = session.send(Frame.Text(text))
                    })
                    attempt = 0
                    for (frame in incoming) {
                        if (frame is Frame.Text) mux.incoming(frame.readText())
                    }
                    lastError = closeReason.await()?.message?.takeIf { it.isNotBlank() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                mux.socketDown(); throw e
            } catch (e: Throwable) {
                lastError = e.message ?: e::class.simpleName
            }
            mux.socketDown()
            // Distinguish auth loss from network loss with a cheap HTTP probe.
            val authOk = runCatching { httpLayer.probe() }.getOrElse { true }
            if (!authOk) {
                mux.setState(ConnState.AuthRequired)
                return
            }
            attempt++
            val wait = StreamMux.backoffMs(attempt)
            mux.setState(ConnState.Reconnecting(attempt, wait, lastError))
            delay(wait)
        }
    }

    /** Reconnect now (skips the backoff wait). */
    fun reconnectNow() = start()

    override suspend fun <T> call(endpoint: String, args: JsonObject, result: KSerializer<T>): T =
        httpLayer.call(endpoint, args, result)

    suspend fun callRaw(endpoint: String, args: JsonObject): JsonElement = httpLayer.callRaw(endpoint, args)

    override fun <T> stream(endpoint: String, args: JsonObject, item: KSerializer<T>): Flow<T> =
        mux.open(endpoint, args).map { json.decodeFromJsonElement(item, it) }

    fun streamRaw(endpoint: String, args: JsonObject): Flow<JsonElement> = mux.open(endpoint, args)

    /** Answers a waterfall (`POST api/$events/result`). [value] null ⇒ `{kind:"result"}` without value. */
    suspend fun answerWaterfall(w: Waterfall, value: JsonElement?) {
        mux.removeWaterfall(w.eventId)
        httpLayer.callRaw("\$events/result", buildJsonObject {
            put("clientId", w.clientId)
            put("eventId", w.eventId)
            put("outcome", buildJsonObject {
                put("kind", "result")
                if (value != null) put("value", value)
            })
        })
    }

    /** Rejects a waterfall (e.g. user dismissed a question); the server sees the handler as failed/cancelled. */
    suspend fun rejectWaterfall(w: Waterfall, message: String = "Cancelled by user") {
        mux.removeWaterfall(w.eventId)
        httpLayer.callRaw("\$events/result", buildJsonObject {
            put("clientId", w.clientId)
            put("eventId", w.eventId)
            put("outcome", buildJsonObject {
                put("kind", "rejected")
                put("error", buildJsonObject { put("name", "AbortError"); put("message", message) })
            })
        })
    }

    /** Declines to handle a waterfall so other clients (or the default) decide. */
    suspend fun passWaterfall(w: Waterfall) {
        mux.removeWaterfall(w.eventId)
        httpLayer.callRaw("\$events/result", buildJsonObject {
            put("clientId", w.clientId)
            put("eventId", w.eventId)
            put("outcome", buildJsonObject { put("kind", "next") })
        })
    }

    fun close() {
        mux.close()
        scope.cancel()
        http.close()
    }
}
