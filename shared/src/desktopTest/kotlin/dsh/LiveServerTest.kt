package dsh

import dsh.data.DshClient
import dsh.model.ChatItem
import dsh.transport.ConnState
import dsh.transport.DshConnection
import dsh.transport.Environment
import dsh.transport.awaitConnected
import dsh.transport.createHttpClient
import dsh.transport.exchangeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live checks against a running DSH. Skipped unless DSH_URL and DSH_TOKEN (or DSH_COOKIE) are set:
 *   DSH_URL=https://host/dsh/ DSH_TOKEN=… ./gradlew :shared:desktopTest --tests 'dsh.LiveServerTest'
 * Read-only unless DSH_LIVE_PROMPT=1 (then it creates a session and sends one tiny prompt).
 */
class LiveServerTest {
    private val url = System.getenv("DSH_URL")
    private val token = System.getenv("DSH_TOKEN")
    private val cookieEnv = System.getenv("DSH_COOKIE")

    private fun live(block: suspend (DshClient) -> Unit) = runBlocking {
        if (url == null || (token == null && cookieEnv == null)) { println("LiveServerTest skipped (no DSH_URL/DSH_TOKEN)"); return@runBlocking }
        val (base, _) = Environment.parseInput(url)
        val cookie = cookieEnv ?: exchangeToken(createHttpClient(), base, token!!)
        println("cookie: ${cookie.substringBefore('=')}=…")
        val client = DshClient(DshConnection(Environment("live", "live", base, cookie)))
        client.start()
        try { block(client) } finally { client.close() }
    }

    @Test
    fun connectsListsAndFollows() = live { client ->
        val connected = client.conn.mux.awaitConnected(20_000)
        assertNotNull(connected, "mux never became ready: ${client.state.value}")
        println("connected clientId=${connected.clientId} home=${connected.home}")
        withTimeout(20_000) { client.sessions.first { it.isNotEmpty() } }
        println("sessions: ${client.sessions.value.size}")
        withTimeout(20_000) { client.workspaces.first { it.isNotEmpty() } }
        println("workspaces: ${client.workspaces.value.map { it.title }}")
        withTimeout(20_000) { client.projections.first { it.isNotEmpty() } }
        val newest = client.sessions.value.values.filter { !it.blank }.maxBy { it.updatedAt }
        val conv = client.conversation(newest.id)
        conv.start()
        val st = withTimeout(30_000) { conv.state.first { it.loaded } }
        println("session ${newest.id}: events=${st.eventCount} items=${st.items.size} hasMore=${st.hasMore}")
        println("item kinds: " + st.items.groupingBy { it::class.simpleName }.eachCount())
        assertTrue(st.items.isNotEmpty())
        val catalog = client.modelCatalog()
        println("models: " + catalog?.groups?.joinToString { g -> g.id + "(" + g.models.size + ")" })
        println("permissions: " + client.permissionCatalog()?.options?.map { it.value })
        println("commands: " + client.commands(newest.id)?.take(8)?.map { it.name })
        if (st.hasMore) { conv.loadOlder(); delay(3000); println("after page: events=${conv.state.value.eventCount}") }

        // Workspace files + terminal round trip (read-only listing; a temporary terminal that is closed afterwards).
        val listing = client.api.workspaceFiles.list(newest.id, ".")
        println("files in ${newest.cwd}: " + listing.entries.take(6).map { it.name })
        if (System.getenv("DSH_LIVE_TERMINAL") == "1") {
            val tid = "t-test-" + dsh.transport.newId().take(6)
            client.api.terminal.create(newest.id, dsh.gen.TerminalCreateRequest(id = tid, cols = 80.0, rows = 24.0))
            val att = "a-test-" + dsh.transport.newId().take(6)
            val screen = dsh.term.VtScreen(80, 24)
            try {
                withTimeout(20_000) {
                    var sent = false
                    client.api.terminal.follow(newest.id, tid, att).first { f ->
                        when (f) {
                            is dsh.gen.TerminalFrame.Snapshot -> screen.feed(f.screen)
                            is dsh.gen.TerminalFrame.Output -> screen.feed(f.data)
                            else -> Unit
                        }
                        if (!sent) { sent = true; client.api.terminal.write(newest.id, tid, att, "echo DSH_TERM_\$((40+2))\r") }
                        screen.text().contains("DSH_TERM_42")
                    }
                }
                println("terminal ok: " + screen.text().lines().first { it.contains("DSH_TERM_42") && !it.contains("echo") })
            } finally { client.api.terminal.close(newest.id, tid) }
        }

        // Reversible settings round trip: set ui-theme.fontSize to its current value, then unset (restores the user layer).
        if (System.getenv("DSH_LIVE_SETTINGS") == "1") {
            fun ns(d: kotlinx.serialization.json.JsonElement) = (d as kotlinx.serialization.json.JsonObject)["namespaces"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .first { (it as kotlinx.serialization.json.JsonObject)["ns"].toString().trim('"') == "ui-theme" } as kotlinx.serialization.json.JsonObject
            val before = ns(client.conn.callRaw("settings/describe", kotlinx.serialization.json.JsonObject(emptyMap())))
            val userBefore = before["user"] as kotlinx.serialization.json.JsonObject
            if (userBefore.isEmpty()) {
                val font = (before["value"] as kotlinx.serialization.json.JsonObject)["fontSize"]!!
                fun op(o: String, v: kotlinx.serialization.json.JsonElement?) = kotlinx.serialization.json.buildJsonObject {
                    put("op", kotlinx.serialization.json.JsonPrimitive(o)); put("path", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("fontSize")))); if (v != null) put("value", v)
                }
                val afterSet = client.conn.callRaw("settings/mutate", kotlinx.serialization.json.buildJsonObject {
                    put("ns", kotlinx.serialization.json.JsonPrimitive("ui-theme")); put("ops", kotlinx.serialization.json.JsonArray(listOf(op("set", font)))); put("expectedRevision", before["revision"]!!)
                }) as kotlinx.serialization.json.JsonObject
                assertTrue((afterSet["user"] as kotlinx.serialization.json.JsonObject).containsKey("fontSize"), "set did not land: $afterSet")
                val afterUnset = client.conn.callRaw("settings/mutate", kotlinx.serialization.json.buildJsonObject {
                    put("ns", kotlinx.serialization.json.JsonPrimitive("ui-theme")); put("ops", kotlinx.serialization.json.JsonArray(listOf(op("unset", null)))); put("expectedRevision", afterSet["revision"]!!)
                }) as kotlinx.serialization.json.JsonObject
                assertTrue((afterUnset["user"] as kotlinx.serialization.json.JsonObject).isEmpty(), "unset did not restore: $afterUnset")
                println("settings round trip ok (revision ${before["revision"]} -> ${afterUnset["revision"]})")
            } else println("settings round trip skipped: ui-theme already has user overrides")
        }

        if (System.getenv("DSH_LIVE_PROMPT") == "1") {
            val cwd = System.getenv("DSH_LIVE_CWD") ?: "/tmp"
            val sid = client.createSession(cwd = cwd)
            assertNotNull(sid)
            val c = client.conversation(sid); c.start()
            withTimeout(20_000) { c.state.first { it.loaded } }
            assertTrue(c.prompt("Reply with exactly the word OK and nothing else. Do not use tools."))
            var sawDraft = false
            val done = withTimeout(180_000) {
                c.state.first { s ->
                    if (s.draft != null) sawDraft = true
                    s.items.any { it is ChatItem.Assistant } && client.sessions.value[sid]?.running == false
                }
            }
            println("prompt reply: " + done.items.filterIsInstance<ChatItem.Assistant>().last().parts + " sawDraft=$sawDraft")
            client.archive(sid)
        }
        assertTrue(client.state.value is ConnState.Connected)
    }
}
