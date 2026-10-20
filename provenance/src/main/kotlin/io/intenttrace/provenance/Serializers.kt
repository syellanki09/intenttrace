package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object FailureClassSerializer : KSerializer<FailureClass> {
    override val descriptor = PrimitiveSerialDescriptor("FailureClass", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: FailureClass) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): FailureClass {
        val id = decoder.decodeString()
        return FailureClass.entries.singleOrNull { it.id == id }
            ?: throw SerializationException("Unknown failure-class ID: $id")
    }
}

object InvariantStatusSerializer : KSerializer<InvariantStatus> {
    override val descriptor = PrimitiveSerialDescriptor("InvariantStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: InvariantStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): InvariantStatus {
        val status = decoder.decodeString()
        return InvariantStatus.entries.singleOrNull { it.name == status }
            ?: throw SerializationException("Unknown invariant status: $status")
    }
}
