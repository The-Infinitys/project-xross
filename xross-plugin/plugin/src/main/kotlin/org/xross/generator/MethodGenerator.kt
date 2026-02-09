package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.Arena

object MethodGenerator {
    fun generateMethods(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition,
        basePackage: String
    ) {
        val selfType = XrossGenerator.getClassName(meta.signature, basePackage)

        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct) generatePublicConstructor(
                    classBuilder,
                    companionBuilder,
                    method,
                    basePackage
                )
                return@forEach
            }

            val returnType = resolveReturnType(method.ret, basePackage)
            val isComplexRet = method.ret is XrossType.Object

            val funBuilder = FunSpec.builder(method.name.toCamelCase().escapeKotlinKeyword())
                .returns(returnType)

            method.args.forEach { arg ->
                funBuilder.addParameter(
                    arg.name.toCamelCase().escapeKotlinKeyword(),
                    resolveReturnType(arg.ty, basePackage)
                )
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = this.segment")
                body.beginControlFlow("if (currentSegment == %T.NULL || !this.aliveFlag.isValid)", MemorySegment::class)
                body.addStatement("throw %T(%S)", NullPointerException::class, "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")

            val callArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) callArgs.add("currentSegment")

            val argPreparationBody = CodeBlock.builder()
            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                when (arg.ty) {
                    is XrossType.RustString -> {
                        argPreparationBody.addStatement("val ${name}Memory = this.arena.allocateFrom($name)")
                        callArgs.add("${name}Memory")
                    }

                    is XrossType.Object -> {
                        argPreparationBody.beginControlFlow(
                            "if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)",
                            MemorySegment::class
                        )
                        argPreparationBody.addStatement(
                            "throw %T(%S)",
                            NullPointerException::class,
                            "Argument '${arg.name}' cannot be NULL or invalid"
                        )
                        argPreparationBody.endControlFlow()
                        callArgs.add("$name.segment")
                    }

                    else -> callArgs.add(name)
                }
            }

            val needsArena = method.args.any { it.ty is XrossType.RustString }
            if (needsArena) {
                body.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class)
                body.add(argPreparationBody.build())
            } else {
                body.add(argPreparationBody.build())
            }

            val handleName = "${method.name.toCamelCase()}Handle"
            val call = "$handleName.invokeExact(${callArgs.joinToString(", ")})"
            body.add(applyMethodCall(method, call, returnType, isComplexRet, selfType))

            if (needsArena) body.endControlFlow()

            body.nextControlFlow("catch (e: Throwable)")
            body.addStatement("throw %T(e)", RuntimeException::class)
            body.endControlFlow()

            funBuilder.addCode(body.build())

            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun resolveReturnType(type: XrossType, basePackage: String): TypeName {
        return when (type) {
            is XrossType.RustString -> String::class.asTypeName()
            is XrossType.Object -> {
                XrossGenerator.getClassName(type.signature, basePackage)
            }

            else -> type.kotlinType
        }
    }

    private fun applyMethodCall(
        method: XrossMethod,
        call: String,
        returnType: TypeName,
        isComplexRet: Boolean,
        selfType: ClassName
    ): CodeBlock {
        val isVoid = method.ret is XrossType.Void
        val safety =
            if (method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance) XrossThreadSafety.Immutable else method.safety
        val body = CodeBlock.builder()

        val useLock = safety == XrossThreadSafety.Lock && method.methodType != XrossMethodType.Static

        if (useLock) {
            if (!isVoid) body.addStatement("var resValue: %T", returnType)
            body.addStatement("var stamp = this.sl.tryOptimisticRead()")
            if (!isVoid) body.add("resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet, selfType))

            body.beginControlFlow("if (!this.sl.validate(stamp))")
            body.addStatement("stamp = this.sl.readLock()")
            body.beginControlFlow("try")
            if (!isVoid) body.add("resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet, selfType))
            body.nextControlFlow("finally")
            body.addStatement("this.sl.unlockRead(stamp)")
            body.endControlFlow()
            body.endControlFlow()
            if (!isVoid) body.addStatement("resValue")
        } else {
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet, selfType))
        }
        return body.build()
    }

    private fun generateInvokeLogic(
        method: XrossMethod,
        call: String,
        returnType: TypeName,
        isComplexRet: Boolean,
        selfType: ClassName
    ): CodeBlock {
        val body = CodeBlock.builder()
        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("this.aliveFlag.isValid = false")
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                }
            }

            method.ret is XrossType.RustString -> {
                body.beginControlFlow("run")
                body.addStatement("val res = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
                body.addStatement("if (res != %T.NULL) xrossFreeStringHandle.invokeExact(res)", MemorySegment::class)
                body.addStatement("str")
                body.endControlFlow()
            }

            isComplexRet -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement(
                    "if (resRaw == %T.NULL) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Native method '${method.name}' returned a NULL reference for type '$returnType'"
                )
                val retType = method.ret as XrossType.Object
                val isSelf = returnType == selfType
                
                if (retType.isOwned) {
                    body.addStatement("val retArena = Arena.ofConfined()")
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    val dropExpr = if (isSelf) "dropHandle" else "%T.dropHandle"
                    val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                    
                    body.addStatement(
                        "val res = resRaw.reinterpret($sizeExpr, retArena) { s -> $dropExpr.invokeExact(s) }",
                        *(if (isSelf) emptyArray() else arrayOf(returnType, returnType))
                    )
                    body.addStatement(
                        "$fromPointerExpr(res, retArena, isArenaOwner = true)",
                        *(if (isSelf) emptyArray() else arrayOf(returnType))
                    )
                } else {
                    val arenaCode = if (method.methodType == XrossMethodType.Static) "Arena.global()" else "this.arena"
                    val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                    body.addStatement(
                        "$fromPointerExpr(resRaw, $arenaCode)",
                        *(if (isSelf) emptyArray() else arrayOf(returnType))
                    )
                }
                body.endControlFlow()
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("this.aliveFlag.isValid = false")
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                }
            }

            else -> body.addStatement("$call as %T", returnType)
        }
        return body.build()
    }

    private fun generatePublicConstructor(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        method: XrossMethod,
        basePackage: String
    ) {
        val pairType =
            Pair::class.asClassName().parameterizedBy(MemorySegment::class.asClassName(), Arena::class.asClassName())
        val factoryBody = CodeBlock.builder()
            .addStatement("val newArena = Arena.ofAuto()")
            .addStatement(
                "val raw = newHandle.invokeExact(${method.args.joinToString(", ") { "arg_" + it.name.toCamelCase() }}) as %T",
                MemorySegment::class
            )
            .beginControlFlow("if (raw == %T.NULL)", MemorySegment::class)
            .addStatement("throw %T(%S)", RuntimeException::class, "Failed to create native object")
            .endControlFlow()
            .addStatement("val res = raw.reinterpret(STRUCT_SIZE, newArena) { s -> dropHandle.invokeExact(s) }")
            .addStatement("return res to newArena")

        companionBuilder.addFunction(
            FunSpec.builder("xrossNewInternal")
                .addModifiers(KModifier.PRIVATE)
                .addParameters(method.args.map {
                    ParameterSpec.builder(
                        "arg_" + it.name.toCamelCase(),
                        resolveReturnType(it.ty, basePackage)
                    ).build()
                })
                .returns(pairType)
                .addCode(factoryBody.build())
                .build()
        )

        classBuilder.addFunction(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("p", pairType)
                .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second"), CodeBlock.of("true"))
                .build()
        )

        classBuilder.addFunction(
            FunSpec.constructorBuilder()
                .addParameters(method.args.map {
                    ParameterSpec.builder(
                        "arg_" + it.name.toCamelCase(),
                        resolveReturnType(it.ty, basePackage)
                    ).build()
                })
                .callThisConstructor(CodeBlock.of("xrossNewInternal(${method.args.joinToString(", ") { "arg_" + it.name.toCamelCase() }})"))
                .build()
        )
    }
}