package dsh.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Minimal surface the generated API stubs (`dsh.gen.*Api`) are written against. */
interface DshTransport {
    val json: Json

    /** Unary `POST api/<endpoint>`; [args] are the wire-named parameters. */
    suspend fun <T> call(endpoint: String, args: JsonObject, result: KSerializer<T>): T

    /** Stream opened over the `api/remote.mux` WebSocket. Survives reconnects (the server re-sends a baseline). */
    fun <T> stream(endpoint: String, args: JsonObject, item: KSerializer<T>): Flow<T>
}
