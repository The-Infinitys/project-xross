package org.xross

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object XrossTypeSerializer : KSerializer<XrossType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("XrossType", PrimitiveKind.STRING)

    private val nameToType = mapOf(
        "Void" to XrossType.Void,
        "Bool" to XrossType.Bool,
        "I8" to XrossType.I8,
        "I16" to XrossType.I16,
        "I32" to XrossType.I32,
        "I64" to XrossType.I64,
        "U16" to XrossType.U16,
        "F32" to XrossType.F32,
        "F64" to XrossType.F64,
        "Pointer" to XrossType.Pointer,
        "String" to XrossType.StringType // Changed from "string" to "String" for consistency
    )

    private val typeToName = nameToType.entries.associate { (name, type) -> type to name }

    override fun deserialize(decoder: Decoder): XrossType {
        val typeString = decoder.decodeString()
        return nameToType[typeString] ?: throw IllegalArgumentException("Unknown XrossType: $typeString")
    }

    override fun serialize(encoder: Encoder, value: XrossType) {
        val name = typeToName[value] ?: throw IllegalStateException("Cannot serialize unknown XrossType: $value")
        encoder.encodeString(name)
    }
}
