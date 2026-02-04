package org.xross.structures

import com.squareup.kotlinpoet.*
import kotlinx.serialization.Serializable
import org.xross.XrossTypeSerializer

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
    object RustString : XrossType() // Rust String

    enum class Ownership { Owned, Ref, MutRef }

    data class RustStruct(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()
    data class RustEnum(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()
    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()

    /** KotlinPoet 用の型取得 */
    val kotlinType: TypeName
        get() = when (this) {
            I32 -> INT
            I64 -> LONG
            F32 -> FLOAT
            F64 -> DOUBLE
            Bool -> BOOLEAN
            I8 -> BYTE
            I16 -> SHORT
            U16 -> CHAR
            Void -> UNIT
            Pointer, RustString, is RustStruct, is RustEnum, is Object ->
                ClassName("java.lang.foreign", "MemorySegment")
        }

    /** FFM API (ValueLayout) へのマッピング */
    val layoutMember: MemberName
        get() = when (this) {
            I32 -> ValueLayouts.JAVA_INT
            I64 -> ValueLayouts.JAVA_LONG
            F32 -> ValueLayouts.JAVA_FLOAT
            F64 -> ValueLayouts.JAVA_DOUBLE
            Bool -> ValueLayouts.JAVA_BYTE // FFMにBooleanLayoutはないためByteで代用
            I8 -> ValueLayouts.JAVA_BYTE
            I16 -> ValueLayouts.JAVA_SHORT
            U16 -> ValueLayouts.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            Pointer, RustString, is RustStruct, is RustEnum, is Object -> ValueLayouts.ADDRESS
        }

    private object ValueLayouts {
        private const val PKG = "java.lang.foreign.ValueLayout"
        val JAVA_INT = MemberName(PKG, "JAVA_INT")
        val JAVA_LONG = MemberName(PKG, "JAVA_LONG")
        val JAVA_FLOAT = MemberName(PKG, "JAVA_FLOAT")
        val JAVA_DOUBLE = MemberName(PKG, "JAVA_DOUBLE")
        val JAVA_BYTE = MemberName(PKG, "JAVA_BYTE")
        val JAVA_SHORT = MemberName(PKG, "JAVA_SHORT")
        val JAVA_CHAR = MemberName(PKG, "JAVA_CHAR")
        val ADDRESS = MemberName(PKG, "ADDRESS")
    }

    val isOwned: Boolean
        get() = when (this) {
            is RustStruct -> ownership == Ownership.Owned
            is RustEnum -> ownership == Ownership.Owned
            is Object -> ownership == Ownership.Owned
            else -> false
        }
}
