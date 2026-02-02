package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.helper.StringHelper.escapeKotlinKeyword
import java.lang.foreign.MemorySegment

object MethodGenerator {

    fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.methods.forEach { method ->
            if (method.isConstructor) {
                generatePublicConstructor(classBuilder, method)
                return@forEach
            }

            val isStringRet = method.ret is XrossType.RustString
            val isStructRet = method.ret is XrossType.Struct

            val returnType = when {
                isStringRet -> String::class.asTypeName()
                isStructRet -> {
                    val struct = method.ret
                    val name = if (struct.name == "Self") meta.structName else struct.name
                    ClassName("", name)
                }

                else -> method.ret.kotlinType
            }

            // メソッド名のエスケープ
            val funBuilder = FunSpec.builder(method.name.toCamelCase().escapeKotlinKeyword())
                .returns(returnType)

            // 引数名のエスケープ
            method.args.forEach {
                funBuilder.addParameter(it.name.toCamelCase().escapeKotlinKeyword(), it.ty.kotlinType)
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = segment")
                body.addStatement(
                    "if (currentSegment == %T.NULL) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Object dropped"
                )
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")

            val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Slice }
            if (needsArena) body.beginControlFlow(
                "%T.ofConfined().use { arena ->",
                ClassName("java.lang.foreign", "Arena")
            )

            val invokeArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) invokeArgs.add("currentSegment")

            // 引数呼び出し時もエスケープした名前を使用
            method.args.forEach {
                invokeArgs.add(it.name.toCamelCase().escapeKotlinKeyword())
            }

            // ハンドル名自体はエスケープ不要（内部プロパティのため）
            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString(", ")})"
            applyMethodCall(body, method, call, isStringRet, isStructRet, returnType)

            if (needsArena) body.endControlFlow()

            body.nextControlFlow("catch (e: Throwable)")
            body.addStatement("throw %T(e)", RuntimeException::class)
            body.endControlFlow()

            funBuilder.addCode(body.build())

            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun applyMethodCall(
        body: CodeBlock.Builder,
        method: XrossMethod,
        call: String,
        isStringRet: Boolean,
        isStructRet: Boolean,
        returnType: TypeName
    ) {
        val lockType = when (method.methodType) {
            XrossMethodType.MutInstance, XrossMethodType.OwnedInstance -> "writeLock().withLock"
            XrossMethodType.ConstInstance -> "readLock().withLock"
            else -> null
        }

        if (lockType != null) body.beginControlFlow("lock.%L", lockType)

        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement(
                    "segment = %T.NULL",
                    MemorySegment::class
                )
            }

            isStringRet -> {
                body.addStatement("val res = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
                body.addStatement("if (res != %T.NULL) xross_free_stringHandle.invokeExact(res)", MemorySegment::class)
                if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement(
                    "segment = %T.NULL",
                    MemorySegment::class
                )
                body.addStatement("str")
            }

            // isStructRet の分岐内
            isStructRet -> {
                val struct = method.ret as XrossType.Struct
                // コンパニオンのプロパティを指すように明示
                val structSize = "Companion.STRUCT_SIZE"

                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val res = if (resRaw == %T.NULL) resRaw else resRaw.reinterpret($structSize)",
                    MemorySegment::class
                )

                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                }
                body.addStatement("%T(res, isBorrowed = ${struct.isReference})", returnType)
            }

            else -> {
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("val res = $call as %T", returnType)
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                    body.addStatement("res")
                } else {
                    body.addStatement("$call as %T", returnType)
                }
            }
        }

        if (lockType != null) body.endControlFlow()
    }

    private fun generatePublicConstructor(classBuilder: TypeSpec.Builder, method: XrossMethod) {
        val builder = FunSpec.constructorBuilder()

        // 引数のセットアップ
        method.args.forEach {
            builder.addParameter(it.name.toCamelCase().escapeKotlinKeyword(), it.ty.kotlinType)
        }
        val args = method.args.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }
        val delegateCode = CodeBlock.builder()
            .add("(\n") // 改行して読みやすく
            .indent()
            .add("(newHandle.invokeExact($args) as %T).let { raw ->\n", MemorySegment::class)
            .add("    if (raw == %T.NULL) raw else raw.reinterpret(Companion.STRUCT_SIZE)\n", MemorySegment::class)
            .add("}),\n")
            .unindent()
            .add("false")
            .build()

        builder.callThisConstructor(delegateCode)
        classBuilder.addFunction(builder.build())
    }
}
