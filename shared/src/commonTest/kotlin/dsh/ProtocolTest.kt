package dsh

import dsh.model.ChatItem
import dsh.model.ConversationFolder
import dsh.model.Draft
import dsh.model.DraftAssembler
import dsh.model.Part
import dsh.model.SessionEvent
import dsh.model.ToolStatus
import dsh.transport.ConnState
import dsh.transport.DshException
import dsh.transport.Environment
import dsh.transport.MuxSocket
import dsh.transport.Multipart
import dsh.transport.StreamMux
import dsh.transport.DshJson
import dsh.transport.get
import dsh.transport.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentTest {
    @Test fun parsesLaunchUrlWithToken() {
        val (base, token) = Environment.parseInput("https://host.example/dsh/?token=abc%2Ddef")
        assertEquals("https://host.example/dsh/", base)
        assertEquals("abc-def", token)
    }

    @Test fun addsSchemeAndSlash() {
        assertEquals("https://host.example/dsh/" to null, Environment.parseInput(" host.example/dsh "))
    }

    @Test fun derivesMuxUrl() {
        assertEquals("wss://h/dsh/api/remote.mux", Environment("1", "n", "https://h/dsh/").muxUrl)
        assertEquals("ws://127.0.0.1:3080/api/remote.mux", Environment("1", "n", "http://127.0.0.1:3080/").muxUrl)
    }
}

class MultipartTest {
    @Test fun inlinesByteAttachmentsAsBase64() {
        val boundary = "XyZ"
        val meta = """{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"name":"a.bin","data":null}},"attachments":[{"path":["data"],"codec":"bytes","part":"bytes-0"}]}"""
        val payload = byteArrayOf(0, 1, 2, -1)
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"bytes-0\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val bytes = body.encodeToByteArray() + payload + "\r\n--$boundary--\r\n".encodeToByteArray()
        val env = Multipart.decodeBinaryEnvelope(bytes, boundary, DshJson)
        assertEquals("r1", env["rpcId"].str)
        val data = env["result"]["value"]["data"].str!!
        assertTrue(Base64.decode(data).contentEquals(payload))
        assertEquals("a.bin", env["result"]["value"]["name"].str)
    }
}

class StreamMuxTest {
    private class FakeSocket : MuxSocket {
        val sent = mutableListOf<JsonObject>()
        override suspend fun send(text: String) { sent += Json.parseToJsonElement(text).jsonObject }
        fun opened(endpoint: String) = sent.lastOrNull { it["type"].str == "open" && it["endpoint"].str == endpoint }?.get("streamId").str
    }

    private suspend fun waitFor(cond: () -> Boolean) = withTimeout(3000) { while (!cond()) delay(5) }

    @Test fun eventsHandshakeThenStreamItemsAndEnd() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mux = StreamMux(scope)
        val sock = FakeSocket()
        mux.socketUp(sock)
        waitFor { sock.opened("\$events") != null }
        val ev = sock.opened("\$events")!!
        // A stream opened before `ready` must wait for it.
        val items = async { mux.open("session/control", JsonObject(emptyMap())).toList() }
        delay(50)
        assertNull(sock.opened("session/control"))
        mux.incoming("""{"type":"item","streamId":"$ev","value":{"type":"ready","clientId":"C1","host":{"home":"/home/u"}}}""")
        waitFor { sock.opened("session/control") != null }
        assertEquals(ConnState.Connected("C1", "/home/u"), mux.state.value)
        val sid = sock.opened("session/control")!!
        mux.incoming("""{"type":"item","streamId":"$sid","value":{"type":"baseline","value":1}}""")
        mux.incoming("""{"type":"item","streamId":"$sid","value":{"type":"projection","value":2}}""")
        mux.incoming("""{"type":"end","streamId":"$sid"}""")
        val got = withTimeout(3000) { items.await() }
        assertEquals(listOf("baseline", "projection"), got.map { it["type"].str })
        scope.cancel()
    }

    @Test fun waterfallsAndCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mux = StreamMux(scope)
        val sock = FakeSocket()
        mux.socketUp(sock)
        waitFor { sock.opened("\$events") != null }
        val ev = sock.opened("\$events")!!
        mux.incoming("""{"type":"item","streamId":"$ev","value":{"type":"ready","clientId":"C1","host":{"home":""}}}""")
        mux.incoming("""{"type":"item","streamId":"$ev","value":{"type":"waterfall","event":"approval/request","eventId":"E1","agentId":"S1","request":{"toolName":"bash"}}}""")
        val w = withTimeout(3000) { mux.waterfalls.first { it.isNotEmpty() } }.single()
        assertEquals("bash", w.request["toolName"].str)
        assertEquals("C1", w.clientId)
        mux.incoming("""{"type":"item","streamId":"$ev","value":{"type":"cancel","eventId":"E1"}}""")
        withTimeout(3000) { mux.waterfalls.first { it.isEmpty() } }
        val emitted = async { mux.events.first() }
        delay(20)
        mux.incoming("""{"type":"item","streamId":"$ev","value":{"type":"emit","event":"api-session/status","args":["S1",true]}}""")
        assertEquals("api-session/status", withTimeout(3000) { emitted.await() }.event)
        scope.cancel()
    }

    @Test fun streamErrorSurfacesAndReconnectReopens() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mux = StreamMux(scope)
        var sock = FakeSocket()
        mux.socketUp(sock)
        waitFor { sock.opened("\$events") != null }
        mux.incoming("""{"type":"item","streamId":"${sock.opened("\$events")}","value":{"type":"ready","clientId":"C1","host":{"home":""}}}""")
        val follow = async { mux.open("session/follow", JsonObject(emptyMap())).take(2).toList() }
        waitFor { sock.opened("session/follow") != null }
        val first = sock.opened("session/follow")!!
        mux.incoming("""{"type":"item","streamId":"$first","value":{"type":"snapshot","n":1}}""")
        // Socket drops; the same logical stream must be re-opened on the next generation.
        mux.socketDown()
        sock = FakeSocket()
        mux.socketUp(sock)
        waitFor { sock.opened("\$events") != null }
        mux.incoming("""{"type":"item","streamId":"${sock.opened("\$events")}","value":{"type":"ready","clientId":"C2","host":{"home":""}}}""")
        waitFor { sock.opened("session/follow") != null }
        mux.incoming("""{"type":"item","streamId":"${sock.opened("session/follow")}","value":{"type":"snapshot","n":2}}""")
        assertEquals(listOf("1", "2"), withTimeout(3000) { follow.await() }.map { it["n"].toString() })

        val failing = async { runCatching { mux.open("terminal/follow", JsonObject(emptyMap())).toList() } }
        waitFor { sock.opened("terminal/follow") != null }
        mux.incoming("""{"type":"error","streamId":"${sock.opened("terminal/follow")}","error":{"code":"terminal/not-found","message":"gone"}}""")
        val err = withTimeout(3000) { failing.await() }.exceptionOrNull()
        assertIs<DshException>(err)
        assertEquals("terminal/not-found", err.code)
        scope.cancel()
    }

    @Test fun backoffIsCappedWithJitter() {
        val r = Random(1)
        for (attempt in 1..20) {
            val cap = longArrayOf(500, 1000, 2000, 4000, 8000, 10000)[(attempt - 1).coerceAtMost(5)]
            val d = StreamMux.backoffMs(attempt, r)
            assertTrue(d in cap / 2..cap, "attempt $attempt: $d not in [${cap / 2}, $cap]")
        }
    }
}

class ConversationFolderTest {
    private fun ev(seq: Long, type: String, data: String, op: String? = null) =
        SessionEvent.parse(Json.parseToJsonElement("""{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data${op?.let { ",\"surfaceOp\":$it" } ?: ""}}"""))!!

    @Test fun foldsMessagesToolsAndApprovals() {
        val items = ConversationFolder().foldAll(listOf(
            ev(0, "permission/preset", """{"preset":"workspace-write"}"""),
            ev(1, "turn/start", """{"turn":1}"""),
            ev(2, "user/message", """{"content":[{"type":"text","text":"hi"}],"role":"user","id":"u1"}"""),
            ev(3, "user/message", """{"content":[{"type":"text","text":"x"}],"role":"user","id":"n1","source":{"form":"notice","kind":"user-approval","summary":"policy changed"}}"""),
            ev(4, "user/message", """{"content":[{"type":"text","text":"sys"}],"role":"user","id":"i1","source":{"form":"instructions","kind":"time-context"}}"""),
            ev(5, "assistant/message", """{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"reasoning","text":"think"},{"type":"text","text":"Running it"},{"type":"tool-call","id":"c1","name":"bash","arguments":"{}"}]}}"""),
            ev(6, "tool/call", """{"turn":1,"step":2,"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}"""),
            ev(7, "approval/asked", """{"id":"a1","toolName":"bash","callId":"c1","reason":"needs network"}"""),
            ev(8, "approval/decided", """{"id":"a1","outcome":"allowed-once"}"""),
            ev(9, "tool/result", """{"turn":1,"step":2,"message":{"role":"tool","toolCallId":"c1","source":{"kind":"tool","callId":"c1"},"content":[{"type":"text","text":"a\nb"}],"id":"r1"}}"""),
            ev(10, "todo/write", """{"todos":[{"content":"do it","status":"in_progress"}]}"""),
            ev(11, "turn/end", """{"turn":1,"reason":{"kind":"error","error":{"code":"x","message":"boom"}}}"""),
        ))
        val visible = items.filterNot { it is ChatItem.Raw }
        assertEquals(listOf("Notice", "User", "Notice", "Assistant", "Tool", "Todos", "TurnEnd"), visible.map { it::class.simpleName })
        val assistant = visible[3] as ChatItem.Assistant
        assertEquals(listOf(Part.Reasoning("think"), Part.Text("Running it")), assistant.parts)
        val tool = visible[4] as ChatItem.Tool
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("a\nb", tool.resultText)
        assertEquals("ls", tool.args!!["command"].str)
        assertEquals("boom", (visible[6] as ChatItem.TurnEnd).error)
    }

    @Test fun rejectedApprovalAndErrors() {
        val items = ConversationFolder().foldAll(listOf(
            ev(1, "tool/call", """{"turn":1,"step":1,"callId":"c1","name":"bash","arguments":"{}"}"""),
            ev(2, "approval/asked", """{"id":"a1","toolName":"bash","callId":"c1"}"""),
        ))
        assertEquals(ToolStatus.AwaitingApproval, (items.single() as ChatItem.Tool).status)
        val f = ConversationFolder()
        f.foldAll(listOf(
            ev(1, "tool/call", """{"turn":1,"step":1,"callId":"c1","name":"bash","arguments":"{}"}"""),
            ev(2, "approval/asked", """{"id":"a1","toolName":"bash","callId":"c1"}"""),
            ev(3, "approval/decided", """{"id":"a1","outcome":"rejected"}"""),
        ))
        assertEquals(ToolStatus.Rejected, (f.result().first() as ChatItem.Tool).status)
    }

    @Test fun surfaceReplaceRemovesRange() {
        val items = ConversationFolder().foldAll(listOf(
            ev(1, "user/message", """{"content":[{"type":"text","text":"one"}],"role":"user","id":"1"}"""),
            ev(2, "user/message", """{"content":[{"type":"text","text":"two"}],"role":"user","id":"2"}"""),
            ev(3, "user/message", """{"content":[{"type":"text","text":"edited"}],"role":"user","id":"3"}""", """{"op":"replace","startSeq":2,"endSeq":2}"""),
        ))
        assertEquals(listOf("one", "edited"), items.filterIsInstance<ChatItem.User>().map { (it.parts.single() as Part.Text).text })
    }
}

class DraftAssemblerTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun assemblesLiveChunks() {
        var d = Draft("a1", 1, 1, 0, emptyMap())
        listOf(
            """{"type":"block-start","index":0,"blockType":"reasoning"}""",
            """{"type":"reasoning-delta","index":0,"text":"hm"}""",
            """{"type":"text-delta","index":1,"text":"Hel"}""",
            """{"type":"text-delta","index":1,"text":"lo"}""",
            """{"type":"tool-call-delta","index":2,"id":"c1","name":"bash","argumentsDelta":"{\"comm"}""",
            """{"type":"tool-call-delta","index":2,"id":"c1","argumentsDelta":"and\":\"ls\"}"}""",
            """{"type":"usage","usage":{}}""",
        ).forEach { d = DraftAssembler.applyChunk(d, j(it)) }
        assertEquals(listOf(Part.Reasoning("hm"), Part.Text("Hello"), Part.ToolCallPart("c1", "bash", "{\"command\":\"ls\"}")), d.toParts())
    }

    @Test fun assemblesReconnectBaselineRuns() {
        var d = Draft("a1", 1, 1, 0, emptyMap())
        listOf(
            """{"type":"reasoning-chunks","time0":1,"index":0,"dt":[0,1],"texts":["a","b"]}""",
            """{"type":"text-chunks","time0":1,"index":1,"dt":[0],"texts":["Hi"]}""",
            """{"type":"chunk","time":3,"chunk":{"type":"text-delta","index":1,"text":"!"}}""",
        ).forEach { d = DraftAssembler.applyRecord(d, j(it)) }
        assertEquals(listOf(Part.Reasoning("ab"), Part.Text("Hi!")), d.toParts())
    }
}
