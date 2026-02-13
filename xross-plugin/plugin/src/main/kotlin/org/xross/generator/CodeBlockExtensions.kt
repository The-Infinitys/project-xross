package org.xross.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import org.xross.generator.FFMConstants.MEMORY_SEGMENT
import org.xross.structures.XrossType

fun CodeBlock.Builder.addResourceConstruction(
    inner: XrossType,
    resRaw: String,
    sizeExpr: CodeBlock,
    fromPointerExpr: CodeBlock,
    dropExpr: CodeBlock,
    flagType: ClassName,
) {
    if (inner.isOwned) {
        addStatement("val retAutoArena = Arena.ofAuto()")
        addStatement("val retOwnerArena = Arena.ofAuto()")
        addStatement("val flag = %T(true)", flagType)
        addStatement(
            "val res = %L.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
            resRaw, sizeExpr, dropExpr
        )
        addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", fromPointerExpr)
    } else {
        addStatement(
            "%L(%L, this.autoArena, sharedFlag = %T(true, this.aliveFlag))",
            fromPointerExpr, resRaw, flagType
        )
    }
}

fun CodeBlock.Builder.addRustStringResolution(call: Any, resultVar: String = "str", isAssignment: Boolean = false, shouldFree: Boolean = true) {
    val resRawName = if (call is String && call.endsWith("Raw")) call else "${resultVar}RawInternal"
    if (!(call is String && call == resRawName)) {
        addStatement("val $resRawName = %L as %T", call, MEMORY_SEGMENT)
    }
    val prefix = if (isAssignment) "" else "val "
    addStatement(
        "$prefix$resultVar = if ($resRawName == %T.NULL) \"\" else $resRawName.reinterpret(%T.MAX_VALUE).getString(0)",
        MEMORY_SEGMENT, Long::class.asTypeName()
    )
    if (shouldFree) {
        addStatement("if ($resRawName != %T.NULL) xrossFreeStringHandle.invokeExact($resRawName)", MEMORY_SEGMENT)
    }
}

fun CodeBlock.Builder.addResultVariantResolution(
    type: XrossType,
    ptrName: String,
    targetTypeName: TypeName,
    selfType: ClassName,
    basePackage: String,
    dropHandleName: String = "dropHandle"
) {
    beginControlFlow("run") // 式として評価できるように run ブロックを追加
    val isSelf = targetTypeName.copy(nullable = false) == selfType
    val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

    when (type) {
        is XrossType.Object -> {
            val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", targetTypeName)
            val dropExpr = if (isSelf) CodeBlock.of(dropHandleName) else CodeBlock.of("%T.dropHandle", targetTypeName)
            val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", targetTypeName)
            addResourceConstruction(type, ptrName, sizeExpr, fromPointerExpr, dropExpr, flagType)
        }
        is XrossType.RustString -> {
            addRustStringResolution(ptrName)
            addStatement("str")
        }
        else -> {
            addStatement("val v = %L.get(%M, 0)", ptrName, type.layoutMember)
            val dropExpr = if (isSelf) CodeBlock.of(dropHandleName) else CodeBlock.of("%T.dropHandle", targetTypeName)
            addStatement("%L.invokeExact(%L)", dropExpr, ptrName)
            addStatement("v")
        }
    }
    endControlFlow()
}
