package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

object CompanionGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val LAYOUT_TYPE = StructLayout::class.asClassName()
    
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        defineProperties(companionBuilder, meta)

        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class)
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class)

        resolveAllHandles(init, meta)

        init.add("\n// --- Native Layout Resolution ---\n")
        init.addStatement("var layoutRaw: %T = %T.NULL", MemorySegment::class, MemorySegment::class)
        init.addStatement("var layoutStr = %S", "")

        init.beginControlFlow("try")
            .addStatement("layoutRaw = layoutHandle.invokeExact() as %T", MemorySegment::class)
            .beginControlFlow("if (layoutRaw != %T.NULL)", MemorySegment::class)
            .addStatement("layoutStr = layoutRaw.reinterpret(%T.MAX_VALUE).getString(0)", Long::class)
            .endControlFlow()
            .nextControlFlow("catch (e: %T)", Throwable::class)
            .addStatement("throw %T(e)", RuntimeException::class)
            .endControlFlow()

        init.beginControlFlow("if (layoutStr.isNotEmpty())")
            .addStatement("val parts = layoutStr.split(';')")
            .addStatement("this.STRUCT_SIZE = parts[0].toLong()")

        when (meta) {
            is XrossDefinition.Struct -> buildStructLayoutInit(init, meta)
            is XrossDefinition.Enum -> buildEnumLayoutInit(init, meta)
            is XrossDefinition.Opaque -> { /* Opaque handles size/layout manually */
            }
        }

        init.beginControlFlow("if (layoutRaw != %T.NULL)", MemorySegment::class)
            .addStatement("xrossFreeStringHandle.invokeExact(layoutRaw)")
            .endControlFlow()
            .nextControlFlow("else")
            .addStatement("this.STRUCT_SIZE = 0L")
            .addStatement("this.LAYOUT = %T.structLayout()", MemoryLayout::class)
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }

    private fun defineProperties(builder: TypeSpec.Builder, meta: XrossDefinition) {
        val handles = mutableListOf("dropHandle", "cloneHandle", "layoutHandle", "xrossFreeStringHandle")

        when (meta) {
            is XrossDefinition.Struct -> {
                handles.add("newHandle")
                meta.fields.forEach {
                    builder.addProperty(
                        PropertySpec.builder(
                            "VH_${it.name.toCamelCase()}", VH_TYPE, KModifier.INTERNAL,
                            KModifier.LATEINIT
                        ).mutable().build()
                    )
                }
            }

            is XrossDefinition.Enum -> {
                handles.add("getTagHandle")
                handles.add("getVariantNameHandle")
                meta.variants.forEach { v ->
                    handles.add("new${v.name}Handle")
                    v.fields.forEach { f ->
                        val baseCamel = f.name.toCamelCase()
                        builder.addProperty(
                            PropertySpec.builder("VH_${v.name}_$baseCamel",
                                VH_TYPE,
                                KModifier.INTERNAL, KModifier.LATEINIT
                            ).mutable().build()
                        )
                        builder.addProperty(
                            PropertySpec.builder(
                                "OFFSET_${v.name}_$baseCamel",
                                Long::class, KModifier.INTERNAL
                            ).mutable().initializer("0L").build()
                        )
                    }
                }
            }

            is XrossDefinition.Opaque -> { /* No additional fields for Opaque here */
            }
        }

        meta.methods.filter { !it.isConstructor }.forEach { handles.add("${it.name.toCamelCase()}Handle") }

        handles.distinct().forEach { name ->
            builder.addProperty(PropertySpec.builder(name, HANDLE_TYPE, KModifier.INTERNAL).mutable().build())
        }

        builder.addProperty(
            PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.PRIVATE).mutable()
                .build()
        )
        builder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.INTERNAL).mutable().initializer("0L").build()
        )
    }

    private fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        init.addStatement(
            "this.xrossFreeStringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))",
            FunctionDescriptor::class,
            ADDRESS
        )

        listOf("drop", "layout", "clone").forEach { suffix ->
            val symbol = "${meta.symbolPrefix}_$suffix"
            val desc = when (suffix) {
                "drop" -> CodeBlock.of("%T.ofVoid(%M)", FunctionDescriptor::class, ADDRESS)
                "layout" -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                else -> CodeBlock.of("%T.of(%M, %M)", FunctionDescriptor::class, ADDRESS, ADDRESS)
            }
            init.addStatement("this.${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
        }

        if (meta is XrossDefinition.Struct) {
            val constructor = meta.methods.find { it.isConstructor && it.name == "new" }
            val argLayouts = constructor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
            val desc = if (argLayouts.isEmpty()) {
                CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
            } else {
                CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
            }
            init.addStatement(
                "this.newHandle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                "${meta.symbolPrefix}_new",
                desc
            )
        } else if (meta is XrossDefinition.Enum) {
            init.addStatement(
                "this.getTagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
                "${meta.symbolPrefix}_get_tag",
                FunctionDescriptor::class,
                JAVA_INT,
                ADDRESS
            )
            init.addStatement(
                "this.getVariantNameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
                "${meta.symbolPrefix}_get_variant_name",
                FunctionDescriptor::class,
                ADDRESS,
                ADDRESS
            )
            meta.variants.forEach { v ->
                val argLayouts = v.fields.map { CodeBlock.of("%M", it.ty.layoutMember) }
                val desc = if (argLayouts.isEmpty()) {
                    CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                } else {
                    CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
                }
                init.addStatement(
                    "this.new${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                    "${meta.symbolPrefix}_new_${v.name}",
                    desc
                )
            }
        }

        meta.methods.filter { !it.isConstructor }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            method.args.forEach { args.add(CodeBlock.of("%M", it.ty.layoutMember)) }
            
            val desc = if (method.ret is XrossType.Void) {
                CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class, args.joinToCode(", "))
            } else {
                val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))
                CodeBlock.of("%T.of(%M%L)", FunctionDescriptor::class, method.ret.layoutMember, argsPart)
            }
            
            init.addStatement(
                "this.${method.name.toCamelCase()}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                method.symbol,
                desc
            )
        }
    }

    private fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        if (meta.fields.isEmpty()) {
            init.addStatement("this.LAYOUT = %T.structLayout(%T.paddingLayout(STRUCT_SIZE))", MemoryLayout::class, MemoryLayout::class)
            return
        }

        init.addStatement("val layouts = mutableListOf<%T>()", MemoryLayout::class)
        init.addStatement("var currentOffsetPos = 0L")

        init.beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val f = parts[i].split(':')")
            .addStatement("if (f.size < 3) continue")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            .beginControlFlow("if (fOffset > currentOffsetPos)")
            .addStatement("layouts.add(%T.paddingLayout(fOffset - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()

        meta.fields.forEachIndexed { idx, field ->
            val branch = if (idx == 0) "if" else "else if"
            init.beginControlFlow("$branch (fName == %S)", field.name)
            
            if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                init.addStatement("layouts.add(%T.paddingLayout(fSize).withName(%S))", MemoryLayout::class, field.name)
            } else {
                init.addStatement("layouts.add(%M.withName(%S))", field.ty.layoutMember, field.name)
            }
            
            init.addStatement("currentOffsetPos = fOffset + fSize")
                .endControlFlow()
        }
        init.endControlFlow()

        init.beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")
            .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()

        init.addStatement("this.LAYOUT = %T.structLayout(*layouts.toTypedArray())", MemoryLayout::class)

        meta.fields.forEach { field ->
            if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                init.addStatement(
                    "this.VH_${field.name.toCamelCase()} = LAYOUT.varHandle(%T.PathElement.groupElement(%S))",
                    MemoryLayout::class, field.name
                )
            }
        }
    }

    private fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement("val variantRegex = %T(%S)", Regex::class, "(\\w+)(?:\\{(.*)})?")
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

            meta.variants.filter { it.fields.isNotEmpty() }.forEach { variant ->
                init.beginControlFlow("if (vName == %S)", variant.name)
                variant.fields.forEach { field ->
                    init.beginControlFlow("if (fName == %S)", field.name)
                        .addStatement("val vLayouts = mutableListOf<%T>()", MemoryLayout::class)
                        .addStatement("if (fOffsetL > 0) vLayouts.add(%T.paddingLayout(fOffsetL))", MemoryLayout::class)
                    
                    if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                        init.addStatement("vLayouts.add(%T.paddingLayout(fSizeL).withName(fName))", MemoryLayout::class)
                    } else {
                        init.addStatement("vLayouts.add(%M.withName(fName).withByteAlignment(1))", field.ty.layoutMember)
                    }
                    
                    init.addStatement("val remaining = STRUCT_SIZE - fOffsetL - fSizeL")
                        .addStatement("if (remaining > 0) vLayouts.add(%T.paddingLayout(remaining))", MemoryLayout::class)
                        .addStatement("val vLayout = %T.structLayout(*vLayouts.toTypedArray())", MemoryLayout::class)
                    
                    if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                        init.addStatement(
                            "this.VH_${variant.name}_${field.name.toCamelCase()} = vLayout.varHandle(%T.PathElement.groupElement(fName))",
                            MemoryLayout::class
                        )
                    }
                    
                    init.addStatement(
                        "this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL"
                    )
                        .endControlFlow()
                }
                init.endControlFlow()
            }
            init.endControlFlow() // fInfo loop
                .endControlFlow() // if vFields not empty
        }
        init.endControlFlow() // parts loop

        init.addStatement(
            "this.LAYOUT = if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout()",
            MemoryLayout::class,
            MemoryLayout::class,
            MemoryLayout::class
        )
    }
}
