package dsh.transport

import kotlinx.serialization.json.JsonElement

/** Error returned by the DSH server (`result.ok == false`) or by the transport. */
open class DshException(
    val code: String,
    message: String,
    val details: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String = "DshException($code: $message)"
}

/** The auth cookie is missing, expired, or revoked: the user must supply a fresh launch token. */
class AuthRequiredException(message: String = "Authentication required — enter a fresh DSH token") :
    DshException("auth/required", message)

/** Non-success HTTP status not covered by the RPC envelope. */
class HttpStatusException(val status: Int, body: String) :
    DshException("http/$status", "HTTP $status${if (body.isNotBlank()) ": ${body.take(300)}" else ""}")
