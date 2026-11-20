package dsh.model

import dsh.transport.arr
import dsh.transport.bool
import dsh.transport.get
import dsh.transport.long
import dsh.transport.num
import dsh.transport.obj
import dsh.transport.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One durable session event as delivered by `session/follow` / `session/page`. */
data class SessionEvent(
    val seq: Long,
    val type: String,
    val time: Long,
    val data: JsonElement?,
    val ignorable: Boolean = false,
    /** Non-null when this event replaces the visible range [replaceStart, replaceEnd]. */
    val replaceStart: Long? = null,
    val replaceEnd: Long? = null,
) {
    companion object {
        /** Parses `{type:"event", event:{...}}` records or bare `{type, seq, time, data}` events. */
        fun parse(record: JsonElement?): SessionEvent? {
            val e = if (record["type"].str == "event" && record["event"] != null) record["event"] else record
            val seq = e["seq"].long ?: return null
            val op = e["surfaceOp"]
            return SessionEvent(
                seq = seq,
                type = e["type"].str ?: return null,
                time = e["time"].long ?: 0,
                data = e["data"],
                ignorable = e["ignorable"].bool == true,
                replaceStart = if (op["op"].str == "replace") op["startSeq"].long else null,
                replaceEnd = if (op["op"].str == "replace") op["endSeq"].long else null,
            )
        }
    }
}

/** Content part of a user / assistant / tool message. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Reasoning(val text: String) : Part
    data class Image(val attachmentId: String, val mediaType: String, val name: String?, val width: Int, val height: Int, val offloaded: Boolean) : Part
    data class File(val attachmentId: String, val name: String, val bytes: Long) : Part
    data class ToolCallPart(val id: String, val name: String, val arguments: String) : Part
    data class Other(val type: String, val raw: JsonElement) : Part

    companion object {
        fun parseAll(content: JsonElement?): List<Part> = content.arr.orEmpty().mapNotNull { parse(it) }

        fun parse(p: JsonElement): Part? = when (val t = p["type"].str) {
            "text" -> Text(p["text"].str.orEmpty())
            "reasoning" -> Reasoning(p["text"].str.orEmpty())
            "image" -> p["attachment"].let { a ->
                Image(a["attachmentId"].str ?: return null, a["mediaType"].str ?: "image/png", a["name"].str,
                    a["width"].num?.toInt() ?: 0, a["height"].num?.toInt() ?: 0, p["offloaded"].bool == true)
            }
            "file" -> p["attachment"].let { a -> File(a["attachmentId"].str ?: return null, a["name"].str ?: "file", a["bytes"].long ?: 0) }
            "tool-call" -> ToolCallPart(p["id"].str.orEmpty(), p["name"].str.orEmpty(), p["arguments"].str.orEmpty())
            "tool-addition", "tool-removal" -> null
            null -> null
            else -> Other(t, p)
        }
    }
}

enum class ToolStatus { Pending, AwaitingApproval, Running, Done, Error, Rejected }

/** Renderable transcript entries. [seq] is the durable seq that produced (or last updated) the item. */
sealed interface ChatItem {
    val key: String
    val seq: Long

    data class User(override val key: String, override val seq: Long, val time: Long, val parts: List<Part>, val messageId: String?) : ChatItem
    data class Notice(override val key: String, override val seq: Long, val kind: String, val text: String, val tone: Tone = Tone.Info) : ChatItem
    data class Assistant(
        override val key: String, override val seq: Long, val time: Long,
        val turn: Int, val step: Int, val parts: List<Part>, val streaming: Boolean = false, val messageId: String? = null,
    ) : ChatItem
    data class Tool(
        override val key: String, override val seq: Long, val time: Long,
        val callId: String, val name: String, val arguments: String,
        val status: ToolStatus, val result: List<Part> = emptyList(), val error: String? = null,
        val approvalReason: String? = null, val meta: JsonElement? = null, val turn: Int = 0, val resultTime: Long? = null,
    ) : ChatItem {
        val args: JsonObject? get() = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        val resultText: String get() = result.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
    }
    data class Todos(override val key: String, override val seq: Long, val todos: List<Todo>) : ChatItem
    data class Deliverables(override val key: String, override val seq: Long, val callId: String, val files: List<Pair<String, String?>>) : ChatItem
    data class TurnEnd(override val key: String, override val seq: Long, val turn: Int, val reason: String, val error: String?) : ChatItem
    data class Compaction(override val key: String, override val seq: Long, val summary: String, val tokens: Long?) : ChatItem
    data class Command(override val key: String, override val seq: Long, val commandId: String, val line: String, val result: String?, val ok: Boolean?) : ChatItem
    /** Any event not otherwise rendered; only shown in the trajectory / "all events" view. */
    data class Raw(override val key: String, override val seq: Long, val type: String, val data: JsonElement?) : ChatItem

    enum class Tone { Info, Warn, Error }
}

data class Todo(val content: String, val status: String)

/** Live, uncommitted assistant output assembled from `assistant-stream` frames. */
data class Draft(
    val attemptId: String,
    val turn: Int,
    val step: Int,
    val nextIndex: Int,
    /** content block index -> block */
    val blocks: Map<Int, DraftBlock>,
) {
    fun toParts(): List<Part> = blocks.toSortedMap().values.mapNotNull {
        when (it.kind) {
            DraftBlock.Kind.Text -> Part.Text(it.text)
            DraftBlock.Kind.Reasoning -> Part.Reasoning(it.text)
            DraftBlock.Kind.ToolCall -> Part.ToolCallPart(it.toolId ?: "", it.toolName ?: "tool", it.text)
        }
    }
}

data class DraftBlock(val kind: Kind, val text: String, val toolId: String? = null, val toolName: String? = null) {
    enum class Kind { Text, Reasoning, ToolCall }
}

object DraftAssembler {
    /** Applies one raw `StreamChunk` (text-delta, reasoning-delta, tool-call-delta, block-start, block-end...). */
    fun applyChunk(d: Draft, chunk: JsonElement?): Draft {
        val idx = chunk["index"].num?.toInt() ?: return d
        val cur = d.blocks[idx]
        val next: DraftBlock? = when (chunk["type"].str) {
            "block-start" -> when (chunk["blockType"].str) {
                "reasoning" -> DraftBlock(DraftBlock.Kind.Reasoning, "")
                "tool-call" -> DraftBlock(DraftBlock.Kind.ToolCall, "")
                else -> DraftBlock(DraftBlock.Kind.Text, "")
            }
            "text-delta" -> (cur ?: DraftBlock(DraftBlock.Kind.Text, "")).let { it.copy(text = it.text + chunk["text"].str.orEmpty()) }
            "reasoning-delta" -> (cur ?: DraftBlock(DraftBlock.Kind.Reasoning, "")).let { it.copy(text = it.text + chunk["text"].str.orEmpty()) }
            "tool-call-delta" -> (cur ?: DraftBlock(DraftBlock.Kind.ToolCall, "")).let {
                it.copy(text = it.text + chunk["argumentsDelta"].str.orEmpty(), toolId = chunk["id"].str ?: it.toolId, toolName = chunk["name"].str ?: it.toolName)
            }
            "block-end" -> {
                val b = chunk["block"]
                when (b["type"].str) {
                    "text" -> DraftBlock(DraftBlock.Kind.Text, b["text"].str ?: cur?.text.orEmpty())
                    "reasoning" -> DraftBlock(DraftBlock.Kind.Reasoning, b["text"].str ?: cur?.text.orEmpty())
                    "tool-call" -> DraftBlock(DraftBlock.Kind.ToolCall, b["arguments"].str ?: cur?.text.orEmpty(), b["id"].str ?: cur?.toolId, b["name"].str ?: cur?.toolName)
                    else -> cur
                }
            }
            else -> cur
        }
        return if (next == null) d else d.copy(blocks = d.blocks + (idx to next))
    }

    /** Applies one compacted record from a reconnect baseline (`text-chunks`, `reasoning-chunks`, `tool-call-chunks`, `chunk`). */
    fun applyRecord(d: Draft, rec: JsonElement?): Draft = when (rec["type"].str) {
        "text-chunks", "reasoning-chunks" -> {
            val idx = rec["index"].num?.toInt() ?: 0
            val kind = if (rec["type"].str == "text-chunks") DraftBlock.Kind.Text else DraftBlock.Kind.Reasoning
            val add = rec["texts"].arr.orEmpty().joinToString("") { it.str.orEmpty() }
            val cur = d.blocks[idx] ?: DraftBlock(kind, "")
            d.copy(blocks = d.blocks + (idx to cur.copy(text = cur.text + add)))
        }
        "tool-call-chunks" -> {
            val idx = rec["index"].num?.toInt() ?: 0
            val add = rec["args"].arr.orEmpty().joinToString("") { it.str.orEmpty() }
            val cur = d.blocks[idx] ?: DraftBlock(DraftBlock.Kind.ToolCall, "")
            d.copy(blocks = d.blocks + (idx to cur.copy(text = cur.text + add, toolId = rec["id"].str ?: cur.toolId, toolName = rec["name"].str ?: cur.toolName)))
        }
        "chunk" -> applyChunk(d, rec["chunk"])
        else -> d
    }
}

/**
 * Folds durable session events into [ChatItem]s. Pure and deterministic: the same events always produce the
 * same items, so paging (prepending older events) simply re-folds.
 */
class ConversationFolder {
    private val items = ArrayList<ChatItem>()
    private val toolIndex = HashMap<String, Int>() // callId -> index in items
    private val commandIndex = HashMap<String, Int>()
    private val pendingApprovals = HashMap<String, String>() // approval id -> callId

    fun result(): List<ChatItem> = items.toList()

    fun foldAll(events: List<SessionEvent>): List<ChatItem> {
        items.clear(); toolIndex.clear(); commandIndex.clear(); pendingApprovals.clear()
        events.forEach { apply(it) }
        return result()
    }

    private fun reindex() {
        toolIndex.clear(); commandIndex.clear()
        items.forEachIndexed { i, it ->
            if (it is ChatItem.Tool) toolIndex[it.callId] = i
            if (it is ChatItem.Command) commandIndex[it.commandId] = i
        }
    }

    fun apply(e: SessionEvent) {
        if (e.replaceStart != null && e.replaceEnd != null) {
            val removed = items.removeAll { it.seq in e.replaceStart..e.replaceEnd && it !is ChatItem.Tool }
            if (removed) reindex()
        }
        val d = e.data
        val k = "${e.type}#${e.seq}"
        when (e.type) {
            "user/message" -> {
                val src = d["source"]
                val form = src["form"].str
                when {
                    form == "notice" -> add(ChatItem.Notice(k, e.seq, src["kind"].str ?: "notice", src["summary"].str ?: textOf(d["content"])))
                    form != null -> Unit // instructions / recall injected for the model; hidden
                    else -> add(ChatItem.User(k, e.seq, e.time, Part.parseAll(d["content"]), d["id"].str))
                }
            }
            "assistant/message" -> {
                val msg = d["message"]
                val parts = Part.parseAll(msg["content"]).filter { it !is Part.ToolCallPart && !(it is Part.Text && it.text.isBlank()) }
                if (parts.isNotEmpty()) {
                    add(ChatItem.Assistant(k, e.seq, e.time, d["turn"].num?.toInt() ?: 0, d["step"].num?.toInt() ?: 0, parts, messageId = msg["id"].str))
                }
                // Tool calls are rendered from tool/call events; register any call parts not yet seen so ordering is stable.
            }
            "tool/call" -> {
                val callId = d["callId"].str ?: return
                val existing = toolIndex[callId]
                val item = ChatItem.Tool(k, e.seq, e.time, callId, d["name"].str ?: "tool", d["arguments"].str ?: "{}",
                    ToolStatus.Running, turn = d["turn"].num?.toInt() ?: 0)
                if (existing != null) items[existing] = item else { toolIndex[callId] = items.size; items += item }
            }
            "tool/result" -> {
                val msg = d["message"]
                val callId = msg["toolCallId"].str ?: msg["source"]["callId"].str ?: return
                val idx = toolIndex[callId]
                val err = d["error"]
                val parts = Part.parseAll(msg["content"])
                val isErr = msg["isError"].bool == true || err != null
                val status = when {
                    err["code"].str?.contains("reject") == true || err["code"].str?.contains("denied") == true -> ToolStatus.Rejected
                    isErr -> ToolStatus.Error
                    else -> ToolStatus.Done
                }
                val errText = err?.let { listOfNotNull(it["reason"].str, it["code"].str).joinToString(": ").ifBlank { null } }
                if (idx != null) {
                    val t = items[idx] as ChatItem.Tool
                    items[idx] = t.copy(status = status, result = parts, error = errText, meta = d["meta"], resultTime = e.time)
                } else {
                    toolIndex[callId] = items.size
                    items += ChatItem.Tool(k, e.seq, e.time, callId, "tool", "{}", status, parts, errText, meta = d["meta"], resultTime = e.time)
                }
            }
            "approval/asked" -> {
                val callId = d["callId"].str
                val reason = d["reason"].str
                if (callId != null && toolIndex[callId] != null) {
                    val i = toolIndex[callId]!!
                    items[i] = (items[i] as ChatItem.Tool).copy(status = ToolStatus.AwaitingApproval, approvalReason = reason)
                    d["id"].str?.let { pendingApprovals[it] = callId }
                } else {
                    add(ChatItem.Notice(k, e.seq, "approval", "Approval requested for ${d["toolName"].str ?: "tool"}" + (reason?.let { ": $it" } ?: ""), ChatItem.Tone.Warn))
                }
            }
            "approval/decided" -> {
                val callId = pendingApprovals.remove(d["id"].str ?: "")
                val outcome = d["outcome"].str ?: ""
                if (callId != null) {
                    toolIndex[callId]?.let { i ->
                        val t = items[i] as ChatItem.Tool
                        items[i] = t.copy(status = if (outcome == "allowed-once") ToolStatus.Running else ToolStatus.Rejected)
                    }
                }
                if (outcome != "allowed-once") add(ChatItem.Notice(k, e.seq, "approval", "Approval $outcome", ChatItem.Tone.Warn))
            }
            "todo/write" -> add(ChatItem.Todos(k, e.seq, d["todos"].arr.orEmpty().map { Todo(it["content"].str.orEmpty(), it["status"].str ?: "pending") }))
            "deliverables/presented" -> add(ChatItem.Deliverables(k, e.seq, d["callId"].str.orEmpty(), d["files"].arr.orEmpty().map { (it["path"].str ?: "") to it["description"].str }))
            "turn/end" -> {
                val r = d["reason"]
                val kind = r["kind"].str ?: "completed"
                // Close any tool still marked running in this turn.
                items.forEachIndexed { i, it -> if (it is ChatItem.Tool && (it.status == ToolStatus.Running || it.status == ToolStatus.AwaitingApproval)) items[i] = it.copy(status = if (kind == "completed") ToolStatus.Done else ToolStatus.Error) }
                if (kind != "completed") add(ChatItem.TurnEnd(k, e.seq, d["turn"].num?.toInt() ?: 0, kind, r["error"]["message"].str))
            }
            "compaction/summary" -> add(ChatItem.Compaction(k, e.seq, textOf(d["summary"]).ifBlank { "Context compacted" }, d["shadowedTokenCount"].long))
            "compaction/start" -> add(ChatItem.Notice(k, e.seq, "compaction", "Compacting context…"))
            "command/run" -> {
                commandIndex[d["commandId"].str ?: k] = items.size
                add(ChatItem.Command(k, e.seq, d["commandId"].str ?: k, "/" + (d["name"].str ?: "") + (d["args"].str ?: ""), null, null))
            }
            "command/done" -> {
                val i = commandIndex[d["commandId"].str ?: ""]
                if (i != null) {
                    val c = items[i] as ChatItem.Command
                    items[i] = c.copy(result = d["text"].str ?: d["message"].str, ok = d["kind"].str == "success")
                }
            }
            "model/selection" -> add(ChatItem.Notice(k, e.seq, "model", "Model: ${d["provider"].str}/${d["model"].str}" + (d["reasoningEffort"].str?.let { " · effort $it" } ?: "")))
            "permission/preset" -> add(ChatItem.Notice(k, e.seq, "permission", "Permission mode: ${d["preset"].str}"))
            "approval/policy" -> add(ChatItem.Notice(k, e.seq, "approval-policy", "Approval policy: ${d["policy"].str}"))
            "plan/mode" -> add(ChatItem.Notice(k, e.seq, "plan", if (d["active"].bool == true) "Plan mode on" else "Plan mode off"))
            "goal/change" -> add(ChatItem.Notice(k, e.seq, "goal", "Goal ${d["operation"].str}: ${d["goal"]["objective"].str ?: ""}".trim().trimEnd(':')))
            "llm/retry" -> add(ChatItem.Notice(k, e.seq, "retry", "Retrying (${d["retry"].long}): ${d["failure"]["message"].str}", ChatItem.Tone.Warn))
            "agent-preset/selected" -> add(ChatItem.Notice(k, e.seq, "preset", "Agent preset: ${d["agentPreset"].str ?: d["preset"].str ?: ""}"))
            "subagent/descriptor" -> add(ChatItem.Notice(k, e.seq, "subagent", "Subagent ${d["label"].str ?: ""} (${d["mode"].str})"))
            "tool-workflow/run-start" -> add(ChatItem.Notice(k, e.seq, "workflow", "Workflow ${d["name"].str} started"))
            "tool-workflow/run-end" -> add(ChatItem.Notice(k, e.seq, "workflow", "Workflow finished"))
            "team/message/delivered", "team/task", "team/member" -> add(ChatItem.Raw(k, e.seq, e.type, d))
            else -> add(ChatItem.Raw(k, e.seq, e.type, d))
        }
    }

    private fun add(item: ChatItem) { items += item }

    companion object {
        fun textOf(content: JsonElement?): String = when {
            content.arr != null -> content.arr!!.joinToString("\n") { it["text"].str.orEmpty() }.trim()
            content.str != null -> content.str!!
            else -> ""
        }

        /** Event types hidden from the chat view (still visible in the trajectory view). */
        val HIDDEN_RAW = setOf(
            "step/start", "step/end", "turn/start", "system/message", "developer/message", "request/header", "request/context",
            "session/title", "session/title-llm-request", "sandbox/mode", "agent/inbox/spliced", "workspace/changes",
            "assistant/attempt", "image/offload", "hook/invoked", "hook/result", "session-log-deepseek/delivery-accepted",
            "session/end-seed", "subagent/catalog", "subagent/model-selection-policy", "feedback/message-put",
            "feedback/message-delete", "feedback/record", "compaction/prune", "compaction/end", "llm/retry-started",
            "web/deepseek-search-llm-request", "tool/ptc-dispatch", "tool/ptc-dispatch-start", "schedule/change",
            "tool-workflow/agent-start", "tool-workflow/agent-end", "team/message/queued",
        )
    }
}

fun List<ChatItem>.visible(showAll: Boolean): List<ChatItem> =
    if (showAll) this else filter { !(it is ChatItem.Raw && it.type in ConversationFolder.HIDDEN_RAW) }

/** Typed view over the session projection map (`session/control` + snapshot projections). */
data class SessionProjections(val values: Map<String, JsonElement?> = emptyMap(), val asOfSeq: Long = -1) {
    val title: String? get() = values["title"].str
    val running: Boolean? get() = values["running"].bool
    val todos: List<Todo>? get() = values["todos"]["todos"].arr?.map { Todo(it["content"].str.orEmpty(), it["status"].str ?: "pending") }
        ?: values["todos"].arr?.map { Todo(it["content"].str.orEmpty(), it["status"].str ?: "pending") }
    val planActive: Boolean get() = values["plan"]["active"].bool == true
    val planPending: Boolean get() = values["plan"]["pending"].bool == true
    val goal: JsonElement? get() = values["goal"]?.takeIf { it is JsonObject }
    val permission: String? get() = values["permissions"]["currentValue"].str
    val agentPreset: String? get() = values["agentPreset"].str
    val modelNext: Pair<String, String>? get() = values["modelSelection"]["next"].let { m -> m["provider"].str?.let { p -> p to (m["model"].str ?: "") } }
    val reasoningEffort: String? get() = values["modelSelection"]["next"]["reasoningEffort"].str
    val contextUsed: Long? get() = values["contextPressure"]["projectedTokens"].long
    val contextWindow: Long? get() = values["contextPressure"]["contextWindow"].long
    val queueNextTurn: List<JsonElement> get() = values["inbox"]["next-turn"].arr.orEmpty()
    val queueNextStep: List<JsonElement> get() = values["inbox"]["next-step"].arr.orEmpty()
    val outputTokens: Long? get() = values["tokenUsage"]["outputTokens"].long
    val inputTokens: Long? get() = values["tokenUsage"].let { (it["uncachedInputTokens"].long ?: 0) + (it["cacheReadTokens"].long ?: 0) }
    val subagent: JsonElement? get() = values["subagent"]?.takeIf { it is JsonObject }
    val subagentCatalog: List<JsonElement> get() = values["subagentCatalog"].arr.orEmpty()
    val stats: JsonElement? get() = values["sessionStats"]
    val turnOutline: List<JsonElement> get() = values["turnOutline"].arr.orEmpty()

    fun with(key: String, value: JsonElement?, seq: Long): SessionProjections =
        copy(values = values + (key to value), asOfSeq = maxOf(asOfSeq, seq))

    companion object {
        fun parse(p: JsonElement?): SessionProjections {
            val vals = p["values"].obj ?: return SessionProjections()
            return SessionProjections(vals, p["asOfSeq"].long ?: -1)
        }
    }
}
