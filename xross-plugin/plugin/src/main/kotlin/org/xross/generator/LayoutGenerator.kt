package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asTypeName
import org.xross.generator.util.FFMConstants.MEMORY_LAYOUT
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossThreadSafety

object LayoutGenerator {
    fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        init.addStatement("val layouts = mutableListOf<%T>()", MEMORY_LAYOUT)
        init.addStatement("var currentOffsetPos = 0L")
        init.addStatement("val matchedFields = mutableSetOf<String>()")

        init.beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val f = parts[i].split(':')")
            .addStatement("if (f.size < 3) continue")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            // 対策1: オフセット間の隙間が0より大きい場合のみパディングを追加
            .beginControlFlow("if (fOffset > currentOffsetPos)")
            .addStatement("layouts.add(%T.paddingLayout(fOffset - currentOffsetPos))", MEMORY_LAYOUT)
            .addStatement("currentOffsetPos = fOffset")
            .endControlFlow()
            .beginControlFlow("if (fName in matchedFields)")
            .addStatement("continue")
            .endControlFlow()

        init.beginControlFlow("when (fName)")
        meta.fields.forEach { field ->
            init.beginControlFlow("%S ->", field.name)
            val kotlinSize = field.ty.kotlinSize
            val isComplex = field.ty.isComplex

            if (isComplex) {
                // 対策2: 複合型のサイズが0より大きい場合のみ追加
                init.beginControlFlow("if (fSize > 0)")
                    .addStatement("layouts.add(%T.paddingLayout(fSize).withName(%S))", MEMORY_LAYOUT, field.name)
                    .endControlFlow()
            } else {
                val alignmentCode = if (field.safety == XrossThreadSafety.Atomic) "" else ".withByteAlignment(1)"
                init.addStatement("layouts.add(%L.withName(%S)%L)", field.ty.layoutCode, field.name, alignmentCode)
                // 対策3: フィールドサイズと型のサイズの差分が0より大きい場合のみパディングを追加
                init.beginControlFlow("if (fSize > $kotlinSize)")
                    .addStatement("layouts.add(%T.paddingLayout(fSize - $kotlinSize))", MEMORY_LAYOUT)
                    .endControlFlow()
            }

            init.addStatement("this.OFFSET_${field.name.toCamelCase()} = fOffset")
            if (field.ty.isPrimitive) {
                init.addStatement("this.VH_${field.name.toCamelCase()} = %L.varHandle()", field.ty.layoutCode)
            }
            init.addStatement("currentOffsetPos = fOffset + fSize")
            init.addStatement("matchedFields.add(fName)")
            init.endControlFlow()
        }

        init.beginControlFlow("else ->")
            // 対策4: 未知のフィールドサイズが0より大きい場合のみ追加
            .beginControlFlow("if (fSize > 0)")
            .addStatement("layouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)
            .endControlFlow()
            .addStatement("currentOffsetPos = fOffset + fSize")
            .endControlFlow()
        init.endControlFlow()
        init.endControlFlow() // Close for loop

        // 対策5: 構造体全体のサイズに満たない場合の末尾パディング
        init.addStatement("val finalPadding = STRUCT_SIZE - currentOffsetPos")
        init.beginControlFlow("if (finalPadding > 0)")
            .addStatement("layouts.add(%T.paddingLayout(finalPadding))", MEMORY_LAYOUT)
            .endControlFlow()

        init.addStatement(
            "this.LAYOUT = if (layouts.isEmpty()) { " +
                "if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE))" +
                " else %T.structLayout() " +
                "} else %T.structLayout(*layouts.toTypedArray())",
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
        )
    }

    fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement("val variantRegex = %T(%S)", Regex::class.asTypeName(), "(\\w+)(?:\\{(.*)})?")
            .beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val match = variantRegex.find(parts[i]) ?: continue")
            .addStatement("val vName = match.groupValues[1]")
            .addStatement("val vFields = match.groupValues[2]")

        val anyVariantHasFields = meta.variants.any { it.fields.isNotEmpty() }
        if (anyVariantHasFields) {
            init.beginControlFlow("if (vFields.isNotEmpty())")
                .beginControlFlow("for (fInfo in vFields.split(';'))")
                .addStatement("if (fInfo.isBlank()) continue")
                .addStatement("val f = fInfo.split(':')")
                .addStatement("val fName = f[0]; val fOffsetL = f[1].toLong(); val fSizeL = f[2].toLong()")

            init.beginControlFlow("when (vName)")
            meta.variants.filter { it.fields.isNotEmpty() }.forEach { variant ->
                init.beginControlFlow("%S ->", variant.name)
                init.beginControlFlow("when (fName)")
                variant.fields.forEach { field ->
                    init.beginControlFlow("%S ->", field.name)
                        .addStatement("val vLayouts = mutableListOf<%T>()", MEMORY_LAYOUT)
                        // 対策6: Enumのフィールドオフセットパディング
                        .addStatement("if (fOffsetL > 0) vLayouts.add(%T.paddingLayout(fOffsetL))", MEMORY_LAYOUT)

                    val kotlinSize = field.ty.kotlinSize
                    val isComplex = field.ty.isComplex
                    if (isComplex) {
                        init.beginControlFlow("if (fSizeL > 0)")
                            .addStatement("vLayouts.add(%T.paddingLayout(fSizeL).withName(fName))", MEMORY_LAYOUT)
                            .endControlFlow()
                    } else {
                        val alignmentCode =
                            if (field.safety == XrossThreadSafety.Atomic) "" else ".withByteAlignment(1)"
                        init.addStatement("vLayouts.add(%L.withName(fName)%L)", field.ty.layoutCode, alignmentCode)
                        // 対策7: Enumフィールドのサイズ差分パディング
                        init.beginControlFlow("if (fSizeL > $kotlinSize)")
                            .addStatement("layouts.add(%T.paddingLayout(fSizeL - $kotlinSize))", MEMORY_LAYOUT)
                            .endControlFlow()
                    }

                    // 対策8: Enum構造体の残りのサイズパディング
                    init.addStatement("val remaining = STRUCT_SIZE - fOffsetL - fSizeL")
                        .beginControlFlow("if (remaining > 0)")
                        .addStatement("vLayouts.add(%T.paddingLayout(remaining))", MEMORY_LAYOUT)
                        .endControlFlow()
                        .addStatement("val vLayout = %T.structLayout(*vLayouts.toTypedArray())", MEMORY_LAYOUT)
                        .addStatement("this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL")

                    if (field.ty.isPrimitive) {
                        init.addStatement(
                            "this.VH_${variant.name}_${field.name.toCamelCase()} = %L.varHandle()",
                            field.ty.layoutCode,
                        )
                    }
                    init.endControlFlow()
                }
                init.endControlFlow()
                init.endControlFlow()
            }
            init.endControlFlow()
            init.endControlFlow().endControlFlow()
        }
        init.endControlFlow()
        init.addStatement(
            "this.LAYOUT = if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout()",
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
        )
    }
}
