package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.GeneratorUtils
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.ValueLayout

/**
 * Generates Kotlin methods that wrap native Rust functions using Java FFM.
 */
object MethodGenerator {
    private val MEMORY_SEGMENT = FFMConstants.MEMORY_SEGMENT

    /**
     * Generates Kotlin methods for all methods defined in the metadata.
     */
    fun generateMethods(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition,
        basePackage: String,
    ) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        val isEnum = meta is XrossDefinition.Enum

        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct || meta is XrossDefinition.Opaque) {
                    ConstructorGenerator.generatePublicConstructor(
                        classBuilder,
                        companionBuilder,
                        method,
                        basePackage,
                        selfType,
                    )
                }
                return@forEach
            }

            if (method.name == "drop" || method.name == "layout") return@forEach
            if (isEnum && method.name == "clone") return@forEach

            val returnType = GeneratorUtils.resolveReturnType(method.ret, basePackage)
            val kotlinName = method.name.toCamelCase().escapeKotlinKeyword()
            val funBuilder = FunSpec.builder(kotlinName).returns(returnType)
            if (method.isAsync) funBuilder.addModifiers(KModifier.SUSPEND)

            // Avoid clash with property accessors
            val fields = when (meta) {
                is XrossDefinition.Struct -> meta.fields
                is XrossDefinition.Opaque -> meta.fields
                else -> emptyList()
            }
            val hasClash = fields.any {
                val base = it.name.toCamelCase().replaceFirstChar { c -> c.uppercase() }
                kotlinName == "get$base" || kotlinName == "set$base"
            }
            if (hasClash) {
                funBuilder.addAnnotation(
                    AnnotationSpec.builder(JvmName::class)
                        .addMember("%S", "xross_${method.name.toCamelCase()}")
                        .build(),
                )
            }

            method.args.forEach { arg ->
                funBuilder.addParameter(
                    arg.name.toCamelCase().escapeKotlinKeyword(),
                    GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                )
            }

            if (method.isRaw) {
                // Generate the raw private method first
                val rawKotlinName = "raw${method.name.toCamelCase().replaceFirstChar { it.uppercase() }}"
                val isPanicable = method.handleMode is HandleMode.Panicable
                val isComplexRet = method.ret is XrossType.RustString || method.isAsync || method.ret is XrossType.Vec || method.ret is XrossType.Slice
                val actualReturnType = if (isPanicable) MEMORY_SEGMENT else returnType
                val rawFunBuilder = FunSpec.builder(rawKotlinName).returns(actualReturnType).addModifiers(KModifier.PRIVATE)
                if (method.isAsync) rawFunBuilder.addModifiers(KModifier.SUSPEND)
                method.args.forEach { arg ->
                    rawFunBuilder.addParameter(
                        arg.name.toCamelCase().escapeKotlinKeyword(),
                        GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                    )
                }

                val rawBody = CodeBlock.builder()
                if (method.methodType != XrossMethodType.Static) {
                    rawBody.addStatement("val currentSegment = this.segment")
                    rawBody.beginControlFlow("if (currentSegment == %T.NULL || !this.isValid)", MEMORY_SEGMENT)
                    rawBody.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Object dropped or invalid")
                    rawBody.endControlFlow()
                }

                rawBody.beginControlFlow("return try")
                val callArgs = mutableListOf<CodeBlock>()
                if (method.methodType != XrossMethodType.Static) callArgs.add(CodeBlock.of("currentSegment"))
                val argPrep = CodeBlock.builder()
                val needsArena = method.args.any { it.ty is XrossType.Vec || it.ty is XrossType.Slice || it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }
                val forceConfined = isComplexRet || isPanicable || needsArena
                val arenaForArg = if (forceConfined) {
                    argPrep.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                    GeneratorUtils.prepareArgumentsAndArena(method.args, method.handleMode, argPrep, basePackage, callArgs, checkObjectValidity = true, arenaName = "arena")
                } else {
                    GeneratorUtils.prepareArgumentsAndArena(method, argPrep, basePackage, callArgs, checkObjectValidity = true)
                    null
                }
                val handleName = "${method.name.toCamelCase()}Handle"

                if (isPanicable) {
                    // For raw panicable, we just allocate the result buffer, call, and return the buffer pointer
                    argPrep.addStatement("val outBuf = %L.allocate(%L)", arenaForArg, FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                    val pArgs = mutableListOf(CodeBlock.of("outBuf"))
                    pArgs.addAll(callArgs)
                    argPrep.addStatement("%L.invokeExact(%L)", handleName, pArgs.joinToCode(", "))
                    argPrep.addStatement("outBuf")
                } else if (isComplexRet) {
                    argPrep.addStatement("val outBuf = %L.allocate(%L)", arenaForArg, FFMConstants.XROSS_STRING_LAYOUT_CODE)
                    val pArgs = mutableListOf(CodeBlock.of("outBuf"))
                    pArgs.addAll(callArgs)
                    argPrep.addStatement("%L.invokeExact(%L)", handleName, pArgs.joinToCode(", "))
                    argPrep.add(InvocationGenerator.applyMethodCall(method, CodeBlock.of("outBuf"), returnType, selfType, basePackage, meta = meta))
                } else {
                    val invokeExpr = if (method.ret is XrossType.Void) {
                        CodeBlock.of("%L.invoke(%L)", handleName, callArgs.joinToCode(", "))
                    } else {
                        CodeBlock.of("%L.invokeExact(%L)", handleName, callArgs.joinToCode(", "))
                    }
                    argPrep.add(InvocationGenerator.applyMethodCall(method, invokeExpr, returnType, selfType, basePackage, meta = meta))
                }

                rawBody.add(argPrep.build())
                if (needsArena || forceConfined) rawBody.endControlFlow()
                rawBody.nextControlFlow("catch (e: Throwable)")
                rawBody.addStatement("if (e is %T) throw e", ClassName("$basePackage.xross.runtime", "XrossException"))
                rawBody.addStatement("throw %T(e)", RuntimeException::class.asTypeName())
                rawBody.endControlFlow()

                rawFunBuilder.addCode(rawBody.build())
                if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(rawFunBuilder.build()) else classBuilder.addFunction(rawFunBuilder.build())

                // Now generate the wrapper method with TODO
                val wrapperFunBuilder = FunSpec.builder(kotlinName).returns(actualReturnType)
                if (method.isAsync) wrapperFunBuilder.addModifiers(KModifier.SUSPEND)
                method.args.forEach { arg ->
                    wrapperFunBuilder.addParameter(
                        arg.name.toCamelCase().escapeKotlinKeyword(),
                        GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                    )
                }
                val argNames = method.args.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }
                val callStmt = if (method.ret is XrossType.Void && !isPanicable) "" else "return "
                wrapperFunBuilder.addCode("// TODO: Define how to process arguments and handle panics (if any) before returning\n")
                wrapperFunBuilder.addCode("${callStmt}$rawKotlinName($argNames)\n")

                if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(wrapperFunBuilder.build()) else classBuilder.addFunction(wrapperFunBuilder.build())
                return@forEach
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = this.segment")
                body.beginControlFlow("if (currentSegment == %T.NULL || !this.isValid)", MEMORY_SEGMENT)
                body.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")
            val callArgs = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) callArgs.add(CodeBlock.of("currentSegment"))

            val argPrep = CodeBlock.builder()
            val needsArena = method.args.any {
                it.ty is XrossType.Vec ||
                    it.ty is XrossType.Slice ||
                    it.ty is XrossType.RustString ||
                    it.ty is XrossType.Optional ||
                    it.ty is XrossType.Result
            }

            val isPanicable = method.handleMode is HandleMode.Panicable
            val isComplexRet =
                method.ret is XrossType.RustString || method.isAsync || method.ret is XrossType.Vec || method.ret is XrossType.Slice
            val forceConfined = isComplexRet || isPanicable || needsArena
            val arenaForArg = if (forceConfined) {
                argPrep.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                GeneratorUtils.prepareArgumentsAndArena(
                    method.args,
                    method.handleMode,
                    argPrep,
                    basePackage,
                    callArgs,
                    checkObjectValidity = true,
                    arenaName = "arena",
                )
            } else {
                // Arena が不要な単純な primitive のみの場合
                GeneratorUtils.prepareArgumentsAndArena(
                    method,
                    argPrep,
                    basePackage,
                    callArgs,
                    checkObjectValidity = true,
                )
                null
            }
            val handleName = "${method.name.toCamelCase()}Handle"
            val call = if (isComplexRet || isPanicable) {
                val layout = if (isPanicable) {
                    FFMConstants.XROSS_RESULT_LAYOUT_CODE
                } else if (method.isAsync) {
                    FFMConstants.XROSS_TASK_LAYOUT_CODE
                } else if (method.ret is XrossType.RustString || method.ret is XrossType.Vec || method.ret is XrossType.Slice) {
                    FFMConstants.XROSS_STRING_LAYOUT_CODE
                } else {
                    method.ret.layoutCode
                }
                argPrep.addStatement("val outBuf = %L.allocate(%L)", arenaForArg, layout)
                val pArgs = mutableListOf(CodeBlock.of("outBuf"))
                pArgs.addAll(callArgs)
                argPrep.addStatement("%L.invokeExact(%L)", handleName, pArgs.joinToCode(", "))
                CodeBlock.of("outBuf")
            } else {
                if (method.ret is XrossType.Void) {
                    CodeBlock.of("%L.invoke(%L)", handleName, callArgs.joinToCode(", "))
                } else {
                    CodeBlock.of("%L.invokeExact(%L)", handleName, callArgs.joinToCode(", "))
                }
            }

            body.add(argPrep.build())
            body.add(InvocationGenerator.applyMethodCall(method, call, returnType, selfType, basePackage, meta = meta))

            if (needsArena || forceConfined) body.endControlFlow()
            body.nextControlFlow("catch (e: Throwable)")
            val xrossException = ClassName("$basePackage.xross.runtime", "XrossException")
            body.addStatement("if (e is %T) throw e", xrossException)
            body.addStatement("throw %T(e)", RuntimeException::class.asTypeName())
            body.endControlFlow()

            funBuilder.addCode(body.build())
            if (method.methodType == XrossMethodType.Static) {
                companionBuilder.addFunction(funBuilder.build())
            } else {
                classBuilder.addFunction(funBuilder.build())
            }

            // --- Generate Zero-Copy View Method ---
            if (method.ret is XrossType.Vec || method.ret is XrossType.Slice) {
                val inner = if (method.ret is XrossType.Vec) method.ret.inner else (method.ret as XrossType.Slice).inner
                val viewName = inner.viewClassName
                if (viewName != null) {
                    val viewClass = ClassName("$basePackage.xross.runtime", viewName)
                    val withFunName = "with${method.name.toCamelCase().replaceFirstChar { it.uppercase() }}"
                    val withFunBuilder = FunSpec.builder(withFunName).addTypeVariable(TypeVariableName("R"))
                    if (method.isAsync) withFunBuilder.addModifiers(KModifier.SUSPEND)

                    method.args.forEach { arg ->
                        withFunBuilder.addParameter(
                            arg.name.toCamelCase().escapeKotlinKeyword(),
                            GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                        )
                    }
                    withFunBuilder.addParameter(
                        "block",
                        LambdaTypeName.get(null, viewClass, returnType = TypeVariableName("R")),
                    )
                    withFunBuilder.returns(TypeVariableName("R"))

                    val withBody = CodeBlock.builder()
                    if (method.methodType != XrossMethodType.Static) {
                        withBody.addStatement("val curSeg = this.segment")
                        withBody.beginControlFlow("if (curSeg == %T.NULL || !this.isValid)", MEMORY_SEGMENT)
                        withBody.addStatement(
                            "throw %T(%S)",
                            NullPointerException::class.asTypeName(),
                            "Object dropped or invalid",
                        )
                        withBody.endControlFlow()
                    }

                    withBody.beginControlFlow("return try")
                    val withCallArgs = mutableListOf<CodeBlock>()
                    if (method.methodType != XrossMethodType.Static) withCallArgs.add(CodeBlock.of("curSeg"))

                    val withArgPrep = CodeBlock.builder()
                    val withArenaName = if (method.isAsync) "java.lang.foreign.Arena.ofAuto()" else "arena"

                    if (!method.isAsync) withArgPrep.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")

                    GeneratorUtils.prepareArgumentsAndArena(
                        method.args,
                        method.handleMode,
                        withArgPrep,
                        basePackage,
                        withCallArgs,
                        checkObjectValidity = true,
                        arenaName = withArenaName,
                    )

                    withArgPrep.addStatement(
                        "val outBuf = %L.allocate(%L)",
                        withArenaName,
                        FFMConstants.XROSS_STRING_LAYOUT_CODE,
                    )
                    val pArgs = mutableListOf(CodeBlock.of("outBuf"))
                    pArgs.addAll(withCallArgs)
                    withArgPrep.addStatement("%L.invokeExact(%L)", handleName, pArgs.joinToCode(", "))

                    // Invocation logic tailored for view
                    withArgPrep.beginControlFlow("val res = run")
                    withArgPrep.addStatement("val resRaw = outBuf as %T", MEMORY_SEGMENT)
                    withArgPrep.beginControlFlow(
                        "if (resRaw == %T.NULL || resRaw.get(%T.ADDRESS, 16L) == %T.NULL)",
                        MEMORY_SEGMENT,
                        ValueLayout::class,
                        MEMORY_SEGMENT,
                    )
                    withArgPrep.addStatement(
                        "throw %T(%S)",
                        NullPointerException::class.asTypeName(),
                        "Unexpected NULL return",
                    )
                    withArgPrep.endControlFlow()
                    withArgPrep.addStatement("val view = %T(resRaw)", viewClass)
                    withArgPrep.beginControlFlow("try")
                    withArgPrep.addStatement("block(view)")
                    withArgPrep.nextControlFlow("finally")
                    withArgPrep.addStatement("xrossFreeBufferHandle.invoke(resRaw)")
                    withArgPrep.endControlFlow()
                    withArgPrep.endControlFlow()
                    withArgPrep.addStatement("res")

                    if (!method.isAsync) withArgPrep.endControlFlow()

                    withBody.add(withArgPrep.build())

                    withBody.nextControlFlow("catch (e: Throwable)")
                    withBody.addStatement(
                        "if (e is %T) throw e",
                        ClassName("$basePackage.xross.runtime", "XrossException"),
                    )
                    withBody.addStatement("throw %T(e)", RuntimeException::class.asTypeName())
                    withBody.endControlFlow()

                    withFunBuilder.addCode(withBody.build())
                    if (method.methodType == XrossMethodType.Static) {
                        companionBuilder.addFunction(withFunBuilder.build())
                    } else {
                        classBuilder.addFunction(withFunBuilder.build())
                    }
                }
            }
        }
    }
}
