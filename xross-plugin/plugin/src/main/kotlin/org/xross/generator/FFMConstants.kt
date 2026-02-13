package org.xross.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

object FFMConstants {
    val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    val MEMORY_LAYOUT = MemoryLayout::class.asTypeName()
    val ARENA = Arena::class.asTypeName()
    val LINKER = Linker::class.asTypeName()
    val SYMBOL_LOOKUP = SymbolLookup::class.asTypeName()
    val FUNCTION_DESCRIPTOR = FunctionDescriptor::class.asTypeName()
    val METHOD_HANDLE = MethodHandle::class.asClassName()
    val VAR_HANDLE = VarHandle::class.asClassName()
    val STRUCT_LAYOUT = StructLayout::class.asClassName()
    val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")
    val JAVA_LONG = MemberName(VAL_LAYOUT, "JAVA_LONG")
    val JAVA_BYTE = MemberName(VAL_LAYOUT, "JAVA_BYTE")
    val JAVA_SHORT = MemberName(VAL_LAYOUT, "JAVA_SHORT")
    val JAVA_FLOAT = MemberName(VAL_LAYOUT, "JAVA_FLOAT")
    val JAVA_DOUBLE = MemberName(VAL_LAYOUT, "JAVA_DOUBLE")

    val XROSS_RESULT_LAYOUT_NAME = "XROSS_RESULT_LAYOUT"
}
