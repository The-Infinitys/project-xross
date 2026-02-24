package org.xross.structures

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.Serializable
import org.xross.generator.XrossGenerator
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.FFMConstants.MEMORY_SEGMENT

/**
 * Represents the data types supported by Xross in Kotlin.
 */
@Serializable(with = XrossTypeSerializer::class)
sealed class XrossType {
    object Void : XrossType()
    object Bool : XrossType()
    object I8 : XrossType()
    object U8 : XrossType()
    object I16 : XrossType()
    object U16 : XrossType()
    object I32 : XrossType()
    object U32 : XrossType()
    object I64 : XrossType()
    object U64 : XrossType()
    object ISize : XrossType()
    object USize : XrossType()
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType()

    /**
     * A slice of values (&[*]).
     */
    data class Slice(val inner: XrossType) : XrossType()

    /**
     * An owned vector of values (Vec<T>).
     */
    data class Vec(val inner: XrossType) : XrossType()

    /**
     * Ownership model for bridged types.
     */
    enum class Ownership { Owned, Boxed, Ref, MutRef, Value }

    /**
     * A user-defined object type.
     */
    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()

    /**
     * An optional type.
     */
    data class Optional(val inner: XrossType) : XrossType()

    /**
     * A result type.
     */
    data class Result(val ok: XrossType, val err: XrossType) : XrossType()

    /**
     * An asynchronous type.
     */
    data class Async(val inner: XrossType) : XrossType()

    /**
     * Returns the KotlinPoet [TypeName] for this type.
     */
    val kotlinType: TypeName
        get() = when (this) {
            I32 -> INT
            U32 -> if (XrossGenerator.property.useUnsignedTypes) U_INT else INT
            I64 -> LONG
            U64 -> if (XrossGenerator.property.useUnsignedTypes) U_LONG else LONG
            ISize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT else LONG
            USize -> {
                if (XrossGenerator.property.useUnsignedTypes) {
                    if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) U_INT else U_LONG
                } else {
                    if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT else LONG
                }
            }
            F32 -> FLOAT
            F64 -> DOUBLE
            Bool -> BOOLEAN
            I8 -> BYTE
            U8 -> if (XrossGenerator.property.useUnsignedTypes) U_BYTE else BYTE
            I16 -> SHORT
            U16 -> if (XrossGenerator.property.useUnsignedTypes) U_SHORT else SHORT
            Void -> UNIT
            RustString -> STRING

            is Slice -> when (inner) {
                I32 -> INT_ARRAY
                U32 -> if (XrossGenerator.property.useUnsignedTypes) U_INT_ARRAY else INT_ARRAY
                I64 -> LONG_ARRAY
                U64 -> if (XrossGenerator.property.useUnsignedTypes) U_LONG_ARRAY else LONG_ARRAY
                F32 -> FLOAT_ARRAY
                F64 -> DOUBLE_ARRAY
                I8 -> BYTE_ARRAY
                U8 -> if (XrossGenerator.property.useUnsignedTypes) U_BYTE_ARRAY else BYTE_ARRAY
                I16 -> SHORT_ARRAY
                U16 -> if (XrossGenerator.property.useUnsignedTypes) U_SHORT_ARRAY else SHORT_ARRAY
                Bool -> BOOLEAN_ARRAY
                ISize ->
                    if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT_ARRAY else LONG_ARRAY

                USize ->
                    if (XrossGenerator.property.useUnsignedTypes) {
                        if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) U_INT_ARRAY else U_LONG_ARRAY
                    } else {
                        if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT_ARRAY else LONG_ARRAY
                    }

                else -> LIST.parameterizedBy(inner.kotlinType)
            }

            is Vec -> when (inner) {
                I32 -> INT_ARRAY
                U32 -> if (XrossGenerator.property.useUnsignedTypes) U_INT_ARRAY else INT_ARRAY
                I64 -> LONG_ARRAY
                U64 -> if (XrossGenerator.property.useUnsignedTypes) U_LONG_ARRAY else LONG_ARRAY
                F32 -> FLOAT_ARRAY
                F64 -> DOUBLE_ARRAY
                I8 -> BYTE_ARRAY
                U8 -> if (XrossGenerator.property.useUnsignedTypes) U_BYTE_ARRAY else BYTE_ARRAY
                I16 -> SHORT_ARRAY
                U16 -> if (XrossGenerator.property.useUnsignedTypes) U_SHORT_ARRAY else SHORT_ARRAY
                Bool -> BOOLEAN_ARRAY
                ISize ->
                    if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT_ARRAY else LONG_ARRAY

                USize ->
                    if (XrossGenerator.property.useUnsignedTypes) {
                        if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) U_INT_ARRAY else U_LONG_ARRAY
                    } else {
                        if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT_ARRAY else LONG_ARRAY
                    }

                else -> LIST.parameterizedBy(inner.kotlinType)
            }

            is Optional -> inner.kotlinType.copy(nullable = true)
            is Result -> ok.kotlinType
            is Async -> inner.kotlinType
            Pointer, is Object -> MEMORY_SEGMENT
        }
    val viewClassName: String?
        get() = when (this) {
            I32, U32 -> "XrossIntArrayView"
            F64 -> "XrossDoubleArrayView"
            F32 -> "XrossFloatArrayView"
            I64, U64 -> "XrossLongArrayView"
            ISize, USize ->
                if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) "XrossIntArrayView" else "XrossLongArrayView"

            I8, U8 -> "XrossByteArrayView"
            I16, U16 -> "XrossShortArrayView"
            Bool -> "XrossBooleanArrayView"
            else -> null
        }

    /**
     * Returns the [MemberName] for the Java FFM ValueLayout of this type.
     */
    val layoutMember: MemberName
        get() = when (this) {
            I32, U32 -> FFMConstants.JAVA_INT
            I64, U64 -> FFMConstants.JAVA_LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) FFMConstants.JAVA_INT else FFMConstants.JAVA_LONG
            F32 -> FFMConstants.JAVA_FLOAT
            F64 -> FFMConstants.JAVA_DOUBLE
            Bool -> FFMConstants.JAVA_BYTE
            I8, U8 -> FFMConstants.JAVA_BYTE
            I16, U16 -> FFMConstants.JAVA_SHORT
            Void -> throw IllegalStateException("Void has no layout")
            is Slice, is Vec -> FFMConstants.ADDRESS
            else -> FFMConstants.ADDRESS
        }

    val layoutCodeCritical: CodeBlock
        get() = when (this) {
            is Vec, is Slice -> CodeBlock.of("%M", FFMConstants.CRITICAL_MEMORY_SEGMENT)
            else -> layoutCode
        }

    val layoutCode: CodeBlock
        get() = when (this) {
            is Result -> FFMConstants.XROSS_RESULT_LAYOUT_CODE
            is RustString -> FFMConstants.XROSS_STRING_LAYOUT_CODE
            is Async -> FFMConstants.XROSS_TASK_LAYOUT_CODE
            is Vec, is Slice -> CodeBlock.of("%M", FFMConstants.ADDRESS)
            else -> CodeBlock.of("%M", layoutMember)
        }

    val abiLayoutCode: CodeBlock
        get() = when (this) {
            is Object -> {
                val className = signature.substringAfterLast('.')
                CodeBlock.of("%N.LAYOUT", className) // Need to handle alignment here
            }
            else -> layoutCode
        }

    /**
     * Returns true if this type represents an owned value.
     */
    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            is Result, is Async, is Vec -> true
            else -> false
        }

    val isComplex: Boolean get() = this is Object || this is Optional || this is Result || this is RustString || this is Async || this is Slice || this is Vec
    val isPrimitive: Boolean get() = !isComplex

    /**
     * Returns the size in bytes for the primitive type.
     */
    val kotlinSize
        get() = when (this) {
            is I32, is U32, is F32 -> 4L
            is I64, is U64, is F64, is Pointer, is RustString -> 8L
            is ISize, is USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) 4L else 8L
            is Result -> 16L
            is Async -> 24L
            is Slice, is Vec -> 16L
            is Object -> 8L
            is Bool, is I8, is U8 -> 1L
            is I16, is U16 -> 2L
            is Void -> 0L
            else -> 8L
        }
}

private val U_BYTE = ClassName("kotlin", "UByte")
private val U_SHORT = ClassName("kotlin", "UShort")
private val U_INT = ClassName("kotlin", "UInt")
private val U_LONG = ClassName("kotlin", "ULong")
private val U_BYTE_ARRAY = ClassName("kotlin", "UByteArray")
private val U_SHORT_ARRAY = ClassName("kotlin", "UShortArray")
private val U_INT_ARRAY = ClassName("kotlin", "UIntArray")
private val U_LONG_ARRAY = ClassName("kotlin", "ULongArray")
