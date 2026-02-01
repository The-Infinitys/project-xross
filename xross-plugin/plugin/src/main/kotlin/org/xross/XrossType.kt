package org.xross

import com.squareup.kotlinpoet.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.lang.foreign.ValueLayout

@Serializable(with = XrossTypeSerializer::class)
sealed class XrossType {
    object Void : XrossType()
    object Bool : XrossType()
    object I8 : XrossType()
    object I16 : XrossType()
    object I32 : XrossType()
    object I64 : XrossType()
    object U16 : XrossType()
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object StringType : XrossType()

    @Serializable @SerialName("slice")
    data class Slice(val elementType: XrossType) : XrossType()

    /** KotlinPoet 用の型取得 */
    val kotlinType: TypeName get() = when (this) {
        I32 -> INT
        I64 -> LONG
        F32 -> FLOAT
        F64 -> DOUBLE
        Bool -> BOOLEAN
        I8 -> BYTE
        I16 -> SHORT
        U16 -> CHAR
        Void -> UNIT
        Pointer, StringType, is Slice -> ClassName("java.lang.foreign", "MemorySegment")
    }

    /** FFM API (ValueLayout) へのマッピング */
    val layoutMember: MemberName get() = MemberName("java.lang.foreign.ValueLayout", when (this) {
        I32 -> "JAVA_INT"
        I64 -> "JAVA_LONG"
        F32 -> "JAVA_FLOAT"
        F64 -> "JAVA_DOUBLE"
        Bool -> "JAVA_BOOLEAN"
        I8 -> "JAVA_BYTE"
        I16 -> "JAVA_SHORT"
        U16 -> "JAVA_CHAR"
        Pointer, StringType, is Slice, Void -> "ADDRESS"
    })
}
