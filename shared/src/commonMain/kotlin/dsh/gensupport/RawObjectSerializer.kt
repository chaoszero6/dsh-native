package dsh.gen

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/** Serializer for generated `Unknown` union variants: keeps the raw JSON object verbatim. */
open class RawObjectSerializer<T>(
    name: String,
    private val wrap: (JsonObject) -> T,
    private val unwrap: (T) -> JsonObject,
) : KSerializer<T> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(name, JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): T {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return wrap(el as? JsonObject ?: JsonObject(mapOf("value" to el)))
    }

    override fun serialize(encoder: Encoder, value: T) {
        (encoder as JsonEncoder).encodeJsonElement(unwrap(value))
    }
}
