package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asTypeName
import org.xross.generator.util.FFMConstants.MEMORY_LAYOUT
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossThreadSafety

object LayoutGenerator {
    fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        init.add("\n// --- Offset and VarHandle Resolution ---\n")
        init.addStatement("val layouts = mutableListOf<%T>()", MEMORY_LAYOUT)
        init.addStatement("var currentPos = 0L")

        // Parse and sort fields by offset to handle Rust field reordering
        init.addStatement("val fieldParts = parts.drop(1).map { it.split(':') }.filter { it.size >= 3 }.sortedBy { it[1].toLong() }")

        init.beginControlFlow("for (f in fieldParts)")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            .addStatement("val pad = fOffset - currentPos")
            .beginControlFlow("if (pad > 0)")
            .addStatement("layouts.add(%T.paddingLayout(pad))", MEMORY_LAYOUT)
            .endControlFlow()

        init.beginControlFlow("when (fName)")
        meta.fields.forEach { field ->
            init.beginControlFlow("%S ->", field.name)
            init.addStatement("this.OFFSET_${field.name.toCamelCase()} = fOffset")
            if (field.ty.isComplex) {
                init.addStatement("layouts.add(%T.paddingLayout(fSize).withName(%S))", MEMORY_LAYOUT, field.name)
            } else {
                // Use alignment 1 only for non-atomic fields. Atomic fields MUST have proper alignment for VarHandle volatile access.
                val alignmentCode = if (field.safety == XrossThreadSafety.Atomic) "" else ".withByteAlignment(1)"
                init.addStatement("layouts.add(%L$alignmentCode.withName(%S))", field.ty.layoutCode, field.name)
            }
            init.addStatement("currentPos = fOffset + fSize")
            init.endControlFlow()
        }
        init.beginControlFlow("else ->")
            .addStatement("layouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)
            .addStatement("currentPos = fOffset + fSize")
            .endControlFlow()
        init.endControlFlow()
        init.endControlFlow()

        init.addStatement("val finalPad = STRUCT_SIZE - currentPos")
        init.beginControlFlow("if (finalPad > 0)")
            .addStatement("layouts.add(%T.paddingLayout(finalPad))", MEMORY_LAYOUT)
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

        // Initialize VarHandles for primitives
        meta.fields.filter { it.ty.isPrimitive }.forEach { field ->
            val camel = field.name.toCamelCase()
            init.beginControlFlow("try")
            init.addStatement("this.VH_$camel = this.LAYOUT.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement(%S))", field.name)
            init.nextControlFlow("catch (e: Throwable)")
            init.addStatement("throw IllegalStateException(%S + %S)", "Failed to resolve VarHandle for field: ", field.name)
            init.endControlFlow()
        }
    }

    fun buildStructAbiLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        init.add("\n// --- ABI Layout Resolution (Natural Alignment) ---\n")
        init.addStatement("val abiLayouts = mutableListOf<%T>()", MEMORY_LAYOUT)
        init.addStatement("var abiOffsetPos = 0L")

        init.beginControlFlow("try")
        init.addStatement("val abiFieldParts = parts.drop(1).map { it.split(':') }.filter { it.size >= 3 }.sortedBy { it[1].toLong() }")
        init.beginControlFlow("for (f in abiFieldParts)")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            .addStatement("val padSize = fOffset - abiOffsetPos")
            .beginControlFlow("if (padSize > 0)")
            .addStatement("abiLayouts.add(%T.paddingLayout(padSize))", MEMORY_LAYOUT)
            .endControlFlow()
            .addStatement("abiOffsetPos = fOffset")

        init.beginControlFlow("when (fName)")
        meta.fields.forEach { field ->
            init.beginControlFlow("%S ->", field.name)
            if (field.ty.isComplex) {
                init.addStatement("abiLayouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)
            } else {
                init.addStatement("abiLayouts.add(%L.withName(%S))", field.ty.layoutCode, field.name)
            }
            init.addStatement("abiOffsetPos = fOffset + fSize")
            init.endControlFlow()
        }
        init.beginControlFlow("else ->")
            .addStatement("abiLayouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)
            .addStatement("abiOffsetPos = fOffset + fSize")
            .endControlFlow()
        init.endControlFlow()
        init.endControlFlow()

        init.addStatement("val abiFinalPadding = STRUCT_SIZE - abiOffsetPos")
        init.beginControlFlow("if (abiFinalPadding > 0)")
            .addStatement("abiLayouts.add(%T.paddingLayout(abiFinalPadding))", MEMORY_LAYOUT)
            .endControlFlow()

        init.addStatement(
            "this.ABI_LAYOUT = if (abiLayouts.isEmpty()) { " +
                "if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) " +
                "else %T.structLayout() " +
                "} else %T.structLayout(*abiLayouts.toTypedArray())",
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
        )
        init.nextControlFlow("catch (e: Throwable)")
        init.addStatement("// Fallback to opaque layout if natural alignment fails")
        init.addStatement("this.ABI_LAYOUT = this.LAYOUT")
        init.endControlFlow()
    }

    fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.add("\n// --- Enum Offset and VarHandle Resolution ---\n")
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
                        .addStatement("this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL")

                    if (field.ty.isPrimitive) {
                        init.beginControlFlow("try")
                        // For Enum, we currently define individual VarHandles per variant field.
                        // Since they share the same physical struct layout, we create a specialized layout for validation.
                        init.addStatement("val vLayout = %T.structLayout(%T.paddingLayout(fOffsetL), %L.withByteAlignment(1).withName(fName))", MEMORY_LAYOUT, MEMORY_LAYOUT, field.ty.layoutCode)
                        init.addStatement("this.VH_${variant.name}_${field.name.toCamelCase()} = vLayout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement(fName))")
                        init.nextControlFlow("catch (e: Throwable)")
                        init.addStatement("throw IllegalStateException(%S + %S + %S + %S)", "Failed to resolve VarHandle for enum field: ", variant.name, ".", field.name)
                        init.endControlFlow()
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
