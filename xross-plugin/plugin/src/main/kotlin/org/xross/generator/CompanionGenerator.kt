package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.util.concurrent.locks.StampedLock
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

object CompanionGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val LAYOUT_TYPE = StructLayout::class.asClassName()
    private val SL_TYPE = StampedLock::class.asClassName()
    private val ADDRESS = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        defineProperties(companionBuilder, meta)

        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class)
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class)
            .addStatement("this.sl = %T()", SL_TYPE)

        resolveAllHandles(init, meta)

        init.add("\n// --- Native Layout Resolution ---\n")
        init.addStatement("val layoutRaw: %T", MemorySegment::class)
        init.addStatement("val layoutStr: %T", String::class)
        init.beginControlFlow("try")
            .addStatement("layoutRaw = layoutHandle.invokeExact() as %T", MemorySegment::class)
            .addStatement(
                "layoutStr = if (layoutRaw == %T.NULL) \"\" else layoutRaw.reinterpret(%T.MAX_VALUE).getString(0)",
                MemorySegment::class, Long::class
            )
            .nextControlFlow("catch (e: %T)", Throwable::class)
            .addStatement("throw %T(e)", RuntimeException::class)
            .endControlFlow()

        init.beginControlFlow("if (layoutStr.isNotEmpty())")
            .addStatement("val parts = layoutStr.split(';')")
            .addStatement("this.STRUCT_SIZE = parts[0].toLong()")

        when (meta) {
            is XrossDefinition.Struct -> buildStructLayoutInit(init, meta)
            is XrossDefinition.Enum -> buildEnumLayoutInit(init, meta)
        }

        init.addStatement(
            "if (layoutRaw != %T.NULL) xross_free_stringHandle.invokeExact(layoutRaw)",
            MemorySegment::class
        )
            .nextControlFlow("else")
            .addStatement("this.STRUCT_SIZE = 0L")
            .addStatement("this.LAYOUT = %T.structLayout()", MemoryLayout::class)
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }

    private fun defineProperties(builder: TypeSpec.Builder, meta: XrossDefinition) {
        // StampedLock
        builder.addProperty(PropertySpec.builder("sl", SL_TYPE, KModifier.PRIVATE).mutable().build())

        // ハンドル類 (lateinitにせず、ダミー初期化を避けるため型のみ宣言はできないので、
        // initで代入されることを前提に、ここではlateinitを使わない形式でプロパティを並べる)
        val handleProps = mutableListOf<String>()
        handleProps.addAll(listOf("dropHandle", "cloneHandle", "layoutHandle", "xross_free_stringHandle"))

        when (meta) {
            is XrossDefinition.Struct -> {
                handleProps.add("newHandle")
                meta.fields.forEach {
                    // VarHandle のみ lateinit
                    builder.addProperty(PropertySpec.builder("VH_${it.name.toCamelCase()}", VH_TYPE, KModifier.PRIVATE).addModifiers(KModifier.LATEINIT).mutable().build())
                }
            }
            is XrossDefinition.Enum -> {
                handleProps.add("get_tagHandle")
                meta.variants.forEach { v ->
                    handleProps.add("new_${v.name}Handle")
                    v.fields.forEach { f ->
                        // VarHandle のみ lateinit
                        builder.addProperty(PropertySpec.builder("VH_${v.name}_${f.name.toCamelCase()}", VH_TYPE, KModifier.PRIVATE).addModifiers(KModifier.LATEINIT).mutable().build())
                    }
                }
            }
        }
        meta.methods.filter { !it.isConstructor }.forEach { handleProps.add("${it.name}Handle") }

        // MethodHandle系のプロパティ生成 (lateinitを使わない)
        handleProps.forEach { name ->
            builder.addProperty(PropertySpec.builder(name, HANDLE_TYPE, KModifier.PRIVATE).mutable().build())
        }

        // LAYOUT は StructLayout なので lateinit 無し (init内で代入)
        builder.addProperty(PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.PRIVATE).mutable().build())

        // STRUCT_SIZE
        builder.addProperty(PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE).mutable().initializer("0L").build())

        // CLEANER
        builder.addProperty(PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE)
            .initializer("%T.create()", ClassName("java.lang.ref", "Cleaner")).build())
    }

    private fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        init.addStatement(
            "this.xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))",
            FunctionDescriptor::class, ADDRESS
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
            val desc = if (argLayouts.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
            else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
            init.addStatement("this.newHandle = linker.downcallHandle(lookup.find(%S).get(), %L)", "${meta.symbolPrefix}_new", desc)
        } else if (meta is XrossDefinition.Enum) {
            init.addStatement(
                "this.get_tagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%T.JAVA_INT, %M))",
                "${meta.symbolPrefix}_get_tag", FunctionDescriptor::class, ValueLayout::class, ADDRESS
            )
            meta.variants.forEach { v ->
                val argLayouts = v.fields.map { CodeBlock.of("%M", it.ty.layoutMember) }
                val desc = if (argLayouts.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
                init.addStatement("this.new_${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", "${meta.symbolPrefix}_new_${v.name}", desc)
            }
        }

        meta.methods.filter { !it.isConstructor }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            method.args.forEach { args.add(CodeBlock.of("%M", it.ty.layoutMember)) }
            val desc = if (method.ret is XrossType.Void) CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class, args.joinToCode(", "))
            else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, method.ret.layoutMember, args.joinToCode(", "))
            init.addStatement("this.${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", method.symbol, desc)
        }
    }

    private fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
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
                .addStatement("layouts.add(%M.withName(%S))", field.ty.layoutMember, field.name)
                .addStatement("currentOffsetPos = fOffset + fSize")
                .endControlFlow()
        }
        init.endControlFlow()
        init.beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")
            .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()
        init.addStatement("this.LAYOUT = %T.structLayout(*layouts.toTypedArray())", MemoryLayout::class)
        meta.fields.forEach { field ->
            init.addStatement("this.VH_${field.name.toCamelCase()} = LAYOUT.varHandle(%T.PathElement.groupElement(%S))", MemoryLayout::class, field.name)
        }
    }

    private fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement("val variantRegex = %T(%S)", Regex::class, "(\\w+)(?:\\{(.*)\\})?")
            .beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val match = variantRegex.find(parts[i]) ?: continue")
            .addStatement("val vName = match.groupValues[1]")
            .addStatement("val vFields = match.groupValues[2]")
            .beginControlFlow("if (vFields.isNotEmpty())")
            .beginControlFlow("for (fInfo in vFields.split(';'))")
            .addStatement("if (fInfo.isBlank()) continue")
            .addStatement("val f = fInfo.split(':')")
            .addStatement("val fName = f[0]; val fOffsetL = f[1].toLong(); val fSizeL = f[2].toLong()")
        meta.variants.forEach { variant ->
            init.beginControlFlow("if (vName == %S)", variant.name)
            variant.fields.forEach { field ->
                init.beginControlFlow("if (fName == %S)", field.name)
                    .addStatement(
                        "val vLayout = %T.structLayout(%T.paddingLayout(fOffsetL), %M.withName(fName), %T.paddingLayout(STRUCT_SIZE - fOffsetL - fSizeL))",
                        MemoryLayout::class, MemoryLayout::class, field.ty.layoutMember, MemoryLayout::class
                    )
                    .addStatement(
                        "this.VH_${variant.name}_${field.name.toCamelCase()} = vLayout.varHandle(%T.PathElement.groupElement(fName))",
                        MemoryLayout::class
                    )
                    .endControlFlow()
            }
            init.endControlFlow()
        }
        init.endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement(
                "this.LAYOUT = %T.structLayout(%T.paddingLayout(STRUCT_SIZE))",
                MemoryLayout::class,
                MemoryLayout::class
            )
    }
}
