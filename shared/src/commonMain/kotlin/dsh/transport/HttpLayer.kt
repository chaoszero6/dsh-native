package dsh.transport

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Platform HTTP client (OkHttp on both Android and desktop): no redirects, no read timeout (long polls/WS). */
expect fun createHttpClient(): HttpClient

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/**
 * Exchanges a DSH launch token for the long-lived auth cookie.
 * `GET <base>?token=T` → 303 + `Set-Cookie: dsh-auth-…` (see packages/client/connection/src/browser-auth.ts).
 * @return the cookie as `name=value`.
 */
suspend fun exchangeToken(http: HttpClient, baseUrl: String, token: String): String {
    val resp = http.get(baseUrl + "?token=" + token.trim().encodeURLParameter())
    val cookies = resp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
    val auth = cookies.map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("dsh-auth-") }
    if (auth != null && resp.status.value in 300..399) return auth
    when (resp.status.value) {
        401 -> throw DshException("auth/invalid-token", "The server rejected this token. Tokens change every time DSH restarts — copy the latest one.")
        403 -> throw DshException("auth/forbidden", "The server refused this host (403). Check the URL or DSH --trusted-host.")
        404 -> throw DshException("auth/not-found", "No DSH server found at this URL (404). Make sure it ends with the DSH path, e.g. /dsh/.")
    }
    if (auth != null) return auth
    throw DshException("auth/unexpected", "Unexpected response ${resp.status.value} from $baseUrl — is this a DSH server?")
}

/** Unary RPC + raw HTTP routes for one environment. */
class HttpLayer(
    private val http: HttpClient,
    private val env: () -> Environment,
    private val onAuthLost: () -> Unit,
) {
    private val json = DshJson

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        env().cookie?.let { header(HttpHeaders.Cookie, it) }
    }

    private suspend fun check(resp: HttpResponse) {
        when (resp.status.value) {
            in 200..299 -> Unit
            401 -> { onAuthLost(); throw AuthRequiredException() }
            else -> throw HttpStatusException(resp.status.value, runCatching { resp.bodyAsText() }.getOrDefault(""))
        }
    }

    /** Raw result value (JSON) of `POST api/<endpoint>`. Byte attachments are inlined as base64 strings. */
    suspend fun callRaw(endpoint: String, args: JsonObject): JsonElement {
        val rpcId = newId()
        val body = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
        val resp = http.post(env().apiUrl + endpoint) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        check(resp)
        val mediaType = resp.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
        val envelope: JsonObject
        if (mediaType == "multipart/form-data") {
            val boundary = resp.headers[HttpHeaders.ContentType]!!.split(';')
                .map { it.trim() }.first { it.startsWith("boundary=") }.removePrefix("boundary=").trim('"')
            envelope = Multipart.decodeBinaryEnvelope(resp.bodyAsBytes(), boundary, json)
        } else {
            envelope = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        }
        if (envelope["rpcId"].str != rpcId) throw DshException("transport/rpc-mismatch", "rpcId mismatch for $endpoint")
        val result = envelope["result"].obj ?: throw DshException("transport/bad-envelope", "Missing result for $endpoint")
        if (result["ok"].bool == true) return result["value"] ?: JsonNull
        val err = result["error"].obj
        throw DshException(err["code"].str ?: "unknown", err["message"].str ?: "Request failed: $endpoint", err?.get("details"))
    }

    suspend fun <T> call(endpoint: String, args: JsonObject, result: KSerializer<T>): T =
        json.decodeFromJsonElement(result, callRaw(endpoint, args))

    /** `GET api/file?path=` — media referenced by session events. */
    suspend fun fetchFile(path: String): ByteArray {
        val resp = http.get(env().apiUrl + "file?path=" + path.encodeURLParameter()) { auth() }
        check(resp); return resp.bodyAsBytes()
    }

    /** Authenticated GET of an arbitrary path relative to the API base (e.g. `changes.diff?…`). */
    suspend fun getBytes(relative: String): Pair<ByteArray, String?> {
        val resp = http.get(env().apiUrl + relative) { auth() }
        check(resp); return resp.bodyAsBytes() to resp.headers[HttpHeaders.ContentType]
    }

    suspend fun getText(relative: String): String {
        val resp = http.get(env().apiUrl + relative) { auth() }
        check(resp); return resp.bodyAsText()
    }

    /** Streaming upload: `POST api/session/uploadFileBinary?sessionId=&name=` → `{receiptId, file}`. */
    suspend fun uploadFile(sessionId: String, name: String, bytes: ByteArray, mediaType: String = "application/octet-stream"): JsonObject {
        val resp = http.post(env().apiUrl + "session/uploadFileBinary?sessionId=${sessionId.encodeURLParameter()}&name=${name.encodeURLParameter()}") {
            auth()
            contentType(ContentType.parse(mediaType))
            setBody(bytes)
        }
        check(resp)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        if (body["ok"].bool == false) {
            val e = body["error"]
            throw DshException(e["code"].str ?: "upload/failed", e["message"].str ?: "Upload failed")
        }
        return body["value"].obj ?: body
    }

    /** Verifies the cookie still works with a cheap call. */
    suspend fun probe(): Boolean = try {
        callRaw("session/canOpenWorkspacePath", JsonObject(emptyMap())); true
    } catch (e: AuthRequiredException) { false }
}

/** Minimal multipart/form-data decoder for binary RPC results (packages/client/connection/src/client/rpc.ts). */
object Multipart {
    data class Part(val name: String?, val headers: Map<String, String>, val body: ByteArray)

    fun parse(bytes: ByteArray, boundary: String): List<Part> {
        val delim = ("--$boundary").encodeToByteArray()
        val parts = mutableListOf<Part>()
        var pos = indexOf(bytes, delim, 0)
        while (pos >= 0) {
            var start = pos + delim.size
            if (start + 1 < bytes.size && bytes[start] == '-'.code.toByte() && bytes[start + 1] == '-'.code.toByte()) break
            if (start + 1 < bytes.size && bytes[start] == '\r'.code.toByte()) start += 2
            val next = indexOf(bytes, delim, start)
            if (next < 0) break
            var end = next
            if (end >= 2 && bytes[end - 2] == '\r'.code.toByte() && bytes[end - 1] == '\n'.code.toByte()) end -= 2
            val sep = indexOf(bytes, "\r\n\r\n".encodeToByteArray(), start)
            if (sep in start until end) {
                val headerText = bytes.copyOfRange(start, sep).decodeToString()
                val headers = headerText.split("\r\n").filter { ':' in it }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                val disp = headers["content-disposition"].orEmpty()
                val name = Regex("name=\"([^\"]*)\"").find(disp)?.groupValues?.get(1)
                parts += Part(name, headers, bytes.copyOfRange(sep + 4, end))
            }
            pos = next
        }
        return parts
    }

    fun decodeBinaryEnvelope(bytes: ByteArray, boundary: String, json: kotlinx.serialization.json.Json): JsonObject {
        val parts = parse(bytes, boundary).associateBy { it.name }
        val meta = parts["metadata"] ?: throw DshException("transport/bad-multipart", "multipart response without metadata")
        val envelope = json.parseToJsonElement(meta.body.decodeToString()).jsonObject
        val attachments = envelope["attachments"].arr ?: return envelope
        var value: JsonElement = envelope["result"]["value"] ?: JsonNull
        for (a in attachments) {
            val partName = a["part"].str ?: continue
            val data = parts[partName]?.body ?: continue
            val path = a["path"].arr ?: continue
            value = JsonPath.set(value, path.toList(), kotlinx.serialization.json.JsonPrimitive(Base64.encode(data)))
        }
        val result = buildJsonObject { put("ok", true); put("value", value) }
        return JsonObject(envelope + ("result" to result))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/** Immutable JSON path update. */
object JsonPath {
    fun set(root: JsonElement, path: List<JsonElement>, v: JsonElement): JsonElement {
        if (path.isEmpty()) return v
        val head = path.first()
        val rest = path.drop(1)
        return when (root) {
            is JsonObject -> {
                val k = head.str ?: return root
                JsonObject(root + (k to set(root[k] ?: JsonNull, rest, v)))
            }
            is kotlinx.serialization.json.JsonArray -> {
                val i = head.long?.toInt() ?: return root
                kotlinx.serialization.json.JsonArray(root.mapIndexed { idx, e -> if (idx == i) set(e, rest, v) else e })
            }
            else -> root
        }
    }
}
