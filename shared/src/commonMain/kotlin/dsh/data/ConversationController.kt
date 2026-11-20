package dsh.data

import dsh.gen.SessionCancelRequest
import dsh.gen.SessionFollowRequest
import dsh.gen.SessionFollowRequestAddress
import dsh.gen.SessionPageRequest
import dsh.gen.SessionPromptRequest
import dsh.gen.SessionPromptRequestContentItem
import dsh.gen.SessionUpdateQueueRequest
import dsh.gen.SessionUpdateQueueRequestAction
import dsh.gen.SessionAttachmentRequest
import dsh.model.ChatItem
import dsh.model.ConversationFolder
import dsh.model.Draft
import dsh.model.DraftAssembler
import dsh.model.SessionEvent
import dsh.model.SessionProjections
import dsh.platform.PickedFile
import dsh.transport.DshException
import dsh.transport.DshJson
import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.newId
import dsh.transport.num
import dsh.transport.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.Base64

data class ConversationState(
    val loaded: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val draft: Draft? = null,
    val header: JsonElement? = null,
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val error: String? = null,
    val eventCount: Int = 0,
    val cursor: Long = -1,
) {
    val cwd: String? get() = header["cwd"].str
    val parentSessionId: String? get() = header["parentSession"].str
}

/** Live view of one session (or a subagent child, via [address]). */
class ConversationController(
    private val client: DshClient,
    val sessionId: String,
    private val address: SessionFollowRequestAddress = SessionFollowRequestAddress.Session(sessionId),
) {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val lock = Mutex()
    private var events = ArrayList<SessionEvent>()
    private val folder = ConversationFolder()
    private var job: Job? = null
    private val imageCache = HashMap<String, ByteArray>()

    fun start() {
        if (job?.isActive == true) return
        job = client.scope.launch { followLoop() }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun followLoop() {
        var failures = 0
        while (true) {
            try {
                val req = SessionFollowRequest(address = address, assistantStream = true, maxMessages = 120.0)
                val args = buildJsonObject { put("request", DshJson.encodeToJsonElement(SessionFollowRequest.serializer(), req)) }
                client.conn.streamRaw("session/follow", args).collect { frame ->
                    failures = 0
                    lock.withLock { onFrame(frame) }
                }
                return // server ended the stream (session removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DshException) {
                failures++
                _state.update { it.copy(error = e.message) }
                if (e.code == "session/not-found") return
            } catch (e: Throwable) {
                failures++
            }
            delay((800L * failures).coerceAtMost(10_000))
        }
    }

    private fun onFrame(f: JsonElement) {
        when (f["type"].str) {
            "snapshot" -> {
                events = ArrayList(f["records"].arr.orEmpty().mapNotNull { SessionEvent.parse(it) }.sortedBy { it.seq })
                val items = folder.foldAll(events)
                var draft: Draft? = null
                f["assistantStream"]["activeAttempt"].let { a ->
                    if (a is JsonObject) {
                        var d = Draft(a["attemptId"].str ?: "", a["turn"].num?.toInt() ?: 0, a["step"].num?.toInt() ?: 0, a["nextIndex"].num?.toInt() ?: 0, emptyMap())
                        a["stream"].arr.orEmpty().forEach { d = DraftAssembler.applyRecord(d, it) }
                        draft = d
                    }
                }
                f["projections"]?.let { client.mergeProjections(sessionId, SessionProjections.parse(it)) }
                _state.value = ConversationState(
                    loaded = true, items = items, draft = draft, header = f["header"],
                    hasMore = f["hasMore"].bool == true, eventCount = events.size, cursor = f["cursor"].long ?: -1,
                )
            }
            "event" -> {
                val e = SessionEvent.parse(f["event"]) ?: return
                val last = events.lastOrNull()?.seq ?: -1
                val items: List<ChatItem>
                if (e.seq > last) {
                    events.add(e); folder.apply(e); items = folder.result()
                } else {
                    if (events.any { it.seq == e.seq }) return
                    events.add(e); events.sortBy { it.seq }; items = folder.foldAll(events)
                }
                val clearDraft = e.type == "assistant/message" || e.type == "assistant/attempt" || e.type == "turn/end"
                _state.update { s ->
                    s.copy(items = items, eventCount = events.size, cursor = maxOf(s.cursor, e.seq),
                        draft = if (clearDraft && s.draft != null && e.type == "turn/end") null else s.draft)
                }
            }
            "assistant-stream" -> {
                val fr = f["frame"]
                when (fr["type"].str) {
                    "start" -> _state.update {
                        it.copy(draft = Draft(fr["attemptId"].str ?: "", fr["turn"].num?.toInt() ?: 0, fr["step"].num?.toInt() ?: 0, 0, emptyMap()))
                    }
                    "chunk" -> _state.update { s ->
                        val d = s.draft ?: return@update s
                        if (d.attemptId != fr["attemptId"].str) return@update s
                        s.copy(draft = DraftAssembler.applyChunk(d, fr["chunk"]).copy(nextIndex = (fr["index"].num?.toInt() ?: d.nextIndex) + 1))
                    }
                    "end" -> _state.update { s -> if (s.draft?.attemptId == fr["attemptId"].str) s.copy(draft = null) else s }
                }
            }
        }
    }

    /** Loads an older page of history (`session/page`). */
    fun loadOlder() {
        val s = _state.value
        if (!s.hasMore || s.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        client.scope.launch {
            val oldest = lock.withLock { events.firstOrNull()?.seq } ?: return@launch
            val page = client.guard("Load history") {
                val req = SessionPageRequest(address, throughSeq = s.cursor.toDouble(), beforeSeq = oldest.toDouble(), maxMessages = 120.0)
                client.conn.callRaw("session/page", buildJsonObject { put("request", DshJson.encodeToJsonElement(SessionPageRequest.serializer(), req)) })
            }
            lock.withLock {
                if (page != null) {
                    val older = page["records"].arr.orEmpty().mapNotNull { SessionEvent.parse(it) }
                    val known = events.map { it.seq }.toHashSet()
                    events.addAll(older.filter { it.seq !in known })
                    events.sortBy { it.seq }
                    val items = folder.foldAll(events)
                    _state.update { it.copy(items = items, hasMore = page["hasMore"].bool == true, loadingOlder = false, eventCount = events.size) }
                } else _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /**
     * Sends a prompt. `mode = "queue"` waits for the current turn; `"steer"` injects into the running turn.
     * Images are sent inline (base64); other files are uploaded first and referenced by receipt.
     */
    suspend fun prompt(text: String, files: List<PickedFile> = emptyList(), mode: String = "queue"): Boolean {
        val content = mutableListOf<SessionPromptRequestContentItem>()
        for (f in files) {
            if (f.mediaType in IMAGE_TYPES) {
                content += SessionPromptRequestContentItem.Image(f.mediaType, Base64.encode(f.bytes), f.name)
            } else {
                val receipt = client.guard("Upload ${f.name}") { client.conn.httpLayer.uploadFile(sessionId, f.name, f.bytes, f.mediaType) } ?: return false
                val id = receipt["receiptId"].str ?: return false.also { client.notice("Upload of ${f.name} returned no receipt") }
                content += SessionPromptRequestContentItem.File(id)
            }
        }
        if (text.isNotBlank()) content.add(0, SessionPromptRequestContentItem.Text(text))
        if (content.isEmpty()) return false
        return client.guard("Send") {
            client.api.session.prompt(SessionPromptRequest(newId(), sessionId, mode, content, clientTimeZone = currentTimeZoneId()))
        } != null
    }

    suspend fun cancel() = client.guard("Stop") { client.api.session.cancel(SessionCancelRequest(sessionId)) }

    suspend fun queueRemove(itemId: String) = client.guard("Remove queued message") {
        client.api.session.updateQueue(SessionUpdateQueueRequest(sessionId, itemId, SessionUpdateQueueRequestAction.Remove()))
    }

    suspend fun queueSteer(itemId: String) = client.guard("Steer") {
        client.api.session.updateQueue(SessionUpdateQueueRequest(sessionId, itemId, SessionUpdateQueueRequestAction.Steer()))
    }

    suspend fun queueEdit(itemId: String, text: String) = client.guard("Edit queued message") {
        val content = listOf(dsh.gen.SessionUpdateQueueRequestActionEditContentItem("text", text))
        client.api.session.updateQueue(SessionUpdateQueueRequest(sessionId, itemId, SessionUpdateQueueRequestAction.Edit(content)))
    }

    /** Image bytes for an attachment referenced by an event (cached). */
    suspend fun attachmentBytes(attachmentId: String): ByteArray? {
        imageCache[attachmentId]?.let { return it }
        val v = runCatching { client.api.session.attachment(SessionAttachmentRequest(sessionId, attachmentId)) }.getOrNull() ?: return null
        val bytes = runCatching { Base64.decode(v.data) }.getOrNull() ?: return null
        imageCache[attachmentId] = bytes
        return bytes
    }

    companion object {
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}

expect fun currentTimeZoneId(): String?
