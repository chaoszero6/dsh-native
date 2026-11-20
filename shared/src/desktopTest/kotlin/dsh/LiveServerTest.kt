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
