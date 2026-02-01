package org.xross

import com.squareup.kotlinpoet.*
import java.io.File
import java.lang.foreign.*
import java.lang.invoke.MethodHandle

object XrossGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val CORE_HANDLES = listOf("new", "drop", "clone", "layout")
    private val ADDRESS_LAYOUT = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val className = meta.structName
        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(AutoCloseable::class)

        // 内部ユーティリティ
        classBuilder.addType(buildFieldMemoryInfoType())

        // インスタンスプロパティ
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class)
                .addModifiers(KModifier.PRIVATE).mutable(true)
                .initializer("%T.NULL", MemorySegment::class).build()
        )

        // 生成ロジックの各セクション
        generateConstructor(classBuilder, meta)
        generateFields(classBuilder, meta.fields)
        generateMethods(classBuilder, meta.methods.filter { !it.isConstructor })
        classBuilder.addType(generateCompanion(meta))
        generateCloseMethod(classBuilder)

        FileSpec.builder(targetPackage, className).indent("    ")
            .addImport(
                "java.lang.foreign",
                "ValueLayout",
                "FunctionDescriptor",
                "MemorySegment",
                "Linker",
                "SymbolLookup",
                "Arena"
            )
            .addType(classBuilder.build()).build().writeTo(outputDir)
    }

    private fun generateConstructor(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val constructor = meta.methods.find { it.isConstructor } ?: return
        val builder = FunSpec.constructorBuilder()

        // 引数の追加（エスケープ済み）
        constructor.args.forEach { builder.addParameter(it.name, it.ty.kotlinType) }

        // invokeExact に渡す引数リストの構築
        // 変数名にバッククォートを適用
        val invokeArgs = constructor.args.joinToString(", ") { it.escapeName() }

        builder.beginControlFlow("try")
            // ★ invokeArgs を使用し、バッククォートが必要な変数（val等）に対応
            .addStatement("val raw = newHandle.invokeExact($invokeArgs) as MemorySegment")
            .addStatement("this.segment = if (STRUCT_SIZE > 0) raw.reinterpret(STRUCT_SIZE) else raw")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(%S, e)", "Failed to allocate ${meta.structName}")
            .endControlFlow()

        classBuilder.primaryConstructor(builder.build())
    }

    private fun generateFields(classBuilder: TypeSpec.Builder, fields: List<XrossField>) {
        fields.forEach { field ->
            val prop = PropertySpec.builder(field.name, field.ty.kotlinType).mutable(true)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return segment.get(%M, OFFSET_${field.name}.offset)", field.ty.layoutMember)
                        .build()
                )
                .setter(
                    FunSpec.setterBuilder().addParameter("value", field.ty.kotlinType)
                        .addStatement("segment.set(%M, OFFSET_${field.name}.offset, value)", field.ty.layoutMember)
                        .build()
                )
                .build()
            classBuilder.addProperty(prop)
        }
    }

    private fun generateMethods(classBuilder: TypeSpec.Builder, methods: List<XrossMethod>) {
        methods.forEach { method ->
            val isStringRet = method.ret is XrossType.StringType
            val returnType = if (isStringRet) String::class.asTypeName() else method.ret.kotlinType
            val funBuilder = FunSpec.builder(method.name).returns(returnType)

            method.args.forEach { funBuilder.addParameter(it.name, it.asKotlinType()) }

            funBuilder.beginControlFlow("try")

            // String または Slice がある場合は Arena を使用
            val needsArena = method.args.any { it.ty is XrossType.StringType || it.ty is XrossType.Slice }
            if (needsArena) funBuilder.beginControlFlow("Arena.ofConfined().use { arena ->")

            val invokeArgs = mutableListOf("segment")
            method.args.forEach { arg ->
                val argName = arg.name
                when (val ty = arg.ty) {
                    is XrossType.StringType -> {
                        funBuilder.addStatement("val ${arg.name}Seg = arena.allocateFrom($argName)")
                        invokeArgs.add("${arg.name}Seg")
                    }

                    is XrossType.Slice -> {
                        // 16バイト構造体 (ptr + len) の構築
                        funBuilder.addComment("Construct Fat Pointer for Slice")
                        funBuilder.addStatement("val ${arg.name}Data = arena.allocateArray(${ty.elementType.layoutMember}, $argName.size.toLong())")
                        // 要素をコピーするロジック（簡易版：プリミティブを想定）
                        funBuilder.addStatement("MemorySegment.copy($argName, 0, ${arg.name}Data, 0, $argName.byteSize())")

                        funBuilder.addStatement("val ${arg.name}Slice = arena.allocate(16, 8)")
                        funBuilder.addStatement("${arg.name}Slice.set(%M, 0, ${arg.name}Data)", ADDRESS_LAYOUT)
                        funBuilder.addStatement("${arg.name}Slice.set(ValueLayout.JAVA_LONG, 8, $argName.size.toLong())")
                        invokeArgs.add("${arg.name}Slice")
                    }

                    else -> invokeArgs.add(argName)
                }
            }

            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString()})"
            when {
                method.ret is XrossType.Void -> funBuilder.addStatement(call)
                isStringRet -> {
                    funBuilder.addStatement("val res = $call as MemorySegment")
                    funBuilder.addStatement("if (res == MemorySegment.NULL) return \"\"")
                    funBuilder.addStatement("val str = res.reinterpret(Long.MAX_VALUE).getString(0)")
                    funBuilder.addComment("Release Rust-owned string memory")
                    funBuilder.addStatement("xross_free_stringHandle.invokeExact(res)")
                    funBuilder.addStatement("return str")
                }

                else -> funBuilder.addStatement("return $call as %T", returnType)
            }

            if (needsArena) funBuilder.endControlFlow()
            funBuilder.nextControlFlow("catch (e: Throwable)").addStatement("throw RuntimeException(e)")
                .endControlFlow()
            classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun generateCompanion(meta: XrossClass): TypeSpec {
        val builder = TypeSpec.companionObjectBuilder()

        // ハンドル定義
        (CORE_HANDLES + "xross_free_string").forEach {
            builder.addProperty(PropertySpec.builder("${it}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        meta.methods.filter { !it.isConstructor }.forEach {
            builder.addProperty(PropertySpec.builder("${it.name}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }

        builder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE).mutable().initializer("0L").build()
        )
        meta.fields.forEach {
            builder.addProperty(
                PropertySpec.builder(
                    "OFFSET_${it.name}",
                    ClassName("", "FieldMemoryInfo"),
                    KModifier.PRIVATE
                ).build()
            )
        }

        val init = CodeBlock.builder()
            .addStatement("val linker = Linker.nativeLinker()")
            .addStatement("val lookup = SymbolLookup.loaderLookup()")
            .apply {
                // xross_free_string は全クラス共通のシンボルとして探す
                addStatement(
                    "xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(ValueLayout.ADDRESS))",
                    FunctionDescriptor::class
                )

                // コアハンドル初期化
                CORE_HANDLES.forEach { suffix ->
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("%T.ofVoid(ValueLayout.ADDRESS)", FunctionDescriptor::class)
                        "new" -> {
                            val ctor = meta.methods.find { it.isConstructor }
                            val args = ctor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) }?.joinToCode() ?: ""
                            CodeBlock.of("%T.of(ValueLayout.ADDRESS, %L)", FunctionDescriptor::class, args)
                        }

                        else -> CodeBlock.of("%T.of(ValueLayout.ADDRESS)", FunctionDescriptor::class)
                    }
                    addStatement(
                        "${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        "${meta.symbolPrefix}_$suffix",
                        desc
                    )
                }

                // メソッドハンドル初期化
                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayouts = mutableListOf(CodeBlock.of("ValueLayout.ADDRESS"))
                    method.args.forEach { arg -> argLayouts.add(CodeBlock.of("%M", arg.ty.layoutMember)) }
                    val desc = if (method.ret is XrossType.Void) {
                        CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class, argLayouts.joinToCode())
                    } else {
                        CodeBlock.of(
                            "%T.of(%M, %L)",
                            FunctionDescriptor::class,
                            method.ret.layoutMember,
                            argLayouts.joinToCode()
                        )
                    }
                    addStatement(
                        "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        method.symbol,
                        desc
                    )
                }
            }
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as MemorySegment")
            .addStatement("val layoutStr = if (layoutRaw == MemorySegment.NULL) \"\" else layoutRaw.reinterpret(1024 * 1024).getString(0)")
            .addStatement("val tempMap = layoutStr.split(';').filter { it.isNotBlank() }.associate { part ->")
            .addStatement("    val bits = part.split(':')")
            .addStatement("    bits[0] to FieldMemoryInfo(bits[1].toLong(), bits[2].toLong())")
            .addStatement("}")
            .apply {
                meta.fields.forEach {
                    addStatement(
                        "OFFSET_${it.name} = tempMap[%S] ?: throw IllegalStateException(\"Field ${it.name} not found\")",
                        it.name
                    )
                }
            }
            .addStatement("STRUCT_SIZE = tempMap.values.maxOfOrNull { it.offset + 8 } ?: 0L")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(\"Init failed for ${meta.structName}\", e)").endControlFlow()

        return builder.addInitializerBlock(init.build()).build()
    }

    // ヘルパー: 型に応じた引数の型（SliceならMemorySegmentとして扱う等）
    private fun XrossField.asKotlinType(): TypeName = when (this.ty) {
        is XrossType.StringType -> String::class.asTypeName()
        is XrossType.Slice -> MemorySegment::class.asTypeName() // 呼び出し側が構築済みセグメントを渡す想定
        else -> this.ty.kotlinType
    }

    private fun generateCloseMethod(classBuilder: TypeSpec.Builder) {
        classBuilder.addFunction(
            FunSpec.builder("close").addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != MemorySegment.NULL)")
                .addStatement("try { dropHandle.invokeExact(segment) } catch (e: Throwable) { throw RuntimeException(e) }")
                .addStatement("segment = MemorySegment.NULL")
                .endControlFlow().build()
        )
    }

    private fun buildFieldMemoryInfoType() = TypeSpec.classBuilder("FieldMemoryInfo")
        .addModifiers(KModifier.PRIVATE).primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("offset", Long::class).addParameter("size", Long::class).build()
        )
        .addProperty(PropertySpec.builder("offset", Long::class).initializer("offset").build())
        .addProperty(PropertySpec.builder("size", Long::class).initializer("size").build()).build()

    private val KOTLIN_KEYWORDS = setOf(
        "package",
        "as",
        "typealias",
        "class",
        "this",
        "super",
        "val",
        "var",
        "fun",
        "for",
        "is",
        "in",
        "throw",
        "return",
        "break",
        "continue",
        "object",
        "if",
        "else",
        "while",
        "do",
        "try",
        "when",
        "interface",
        "typeof"
    )

    private fun XrossField.escapeName(): String = if (this.name in KOTLIN_KEYWORDS) "`${this.name}`" else this.name
}

