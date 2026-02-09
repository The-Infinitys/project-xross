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
    object f32 : XrossType()
    object f64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType() // Rust String

    enum class Ownership { Owned, Boxed, Ref, MutRef }

    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()

    val kotlinType: TypeName
        get() = when (this) {
            I32 -> INT
            I64 -> LONG
            f32 -> FLOAT
            f64 -> DOUBLE
            Bool -> BOOLEAN
            I8 -> BYTE
            I16 -> SHORT
            U16 -> CHAR
            Void -> UNIT
            Pointer, RustString, is Object ->
                ClassName("java.lang.foreign", "MemorySegment")
        }

    val layoutMember: MemberName
        get() = when (this) {
            I32 -> ValueLayouts.JAVA_INT
            I64 -> ValueLayouts.JAVA_LONG
            f32 -> ValueLayouts.JAVA_FLOAT
            f64 -> ValueLayouts.JAVA_DOUBLE
            Bool -> ValueLayouts.JAVA_BYTE
            I8 -> ValueLayouts.JAVA_BYTE
            I16 -> ValueLayouts.JAVA_SHORT
            U16 -> ValueLayouts.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            Pointer, RustString, is Object -> ValueLayouts.ADDRESS
        }

    companion object {
        val I8 = org.xross.structures.XrossType.I8
        val I16 = org.xross.structures.XrossType.I16
        val I32 = org.xross.structures.XrossType.I32
        val I64 = org.xross.structures.XrossType.I64
        val U16 = org.xross.structures.XrossType.U16
        val F32 = org.xross.structures.XrossType.f32
        val F64 = org.xross.structures.XrossType.f64
    }

    private object ValueLayouts {
        private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
        val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")
        val JAVA_LONG = MemberName(VAL_LAYOUT, "JAVA_LONG")
        val JAVA_FLOAT = MemberName(VAL_LAYOUT, "JAVA_FLOAT")
        val JAVA_DOUBLE = MemberName(VAL_LAYOUT, "JAVA_DOUBLE")
        val JAVA_BYTE = MemberName(VAL_LAYOUT, "JAVA_BYTE")
        val JAVA_SHORT = MemberName(VAL_LAYOUT, "JAVA_SHORT")
        val JAVA_CHAR = MemberName(VAL_LAYOUT, "JAVA_CHAR")
        val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    }

    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            else -> false
        }
}