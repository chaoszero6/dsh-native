package dsh.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Wire JSON configuration.
 * - `explicitNulls = false`: optional properties are omitted instead of sent as `null` (the server's zod
 *   schemas use `.optional()`, which rejects `null`).
 * - `ignoreUnknownKeys`: newer DSH servers may add fields.
 */
val DshJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

// ---- lenient accessors used by hand-written readers of untyped event payloads ----
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString || it !is JsonNull }?.contentOrNull
val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** Pretty JSON for debug/generic views. */
val PrettyJson: Json = Json { prettyPrint = true; prettyPrintIndent = "  " }
