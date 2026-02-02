package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethod
import org.xross.structures.XrossMethodType
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
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
        val safety = method.safety
        val methodType = method.methodType
        val isVoid = method.ret is XrossType.Void

        // StaticかInstanceかに関わらず、同じ名前 "sl" を参照する
        // ※ HandleGenerator で Companion にも sl を追加した前提
        val lockName = "sl"

        val effectiveSafety = if (methodType == XrossMethodType.MutInstance || methodType == XrossMethodType.OwnedInstance) {
            XrossThreadSafety.Immutable
        } else {
            safety
        }

        when (effectiveSafety) {
            XrossThreadSafety.Unsafe -> {
                generateCallBody(body, method, call, isStringRet, isStructRet, returnType)
            }

            XrossThreadSafety.Lock -> {
                if (!isVoid) body.addStatement("var resValue: %T", returnType)
                body.addStatement("var stamp = %L.tryOptimisticRead()", lockName)

                if (!isVoid) body.add("resValue = ")
                generateCallBody(body, method, call, isStringRet, isStructRet, returnType)

                body.beginControlFlow("if (!%L.validate(stamp))", lockName)
                body.addStatement("stamp = %L.readLock()", lockName)
                body.beginControlFlow("try")
                if (!isVoid) body.add("resValue = ")
                generateCallBody(body, method, call, isStringRet, isStructRet, returnType)
                body.nextControlFlow("finally")
                body.addStatement("%L.unlockRead(stamp)", lockName)
                body.endControlFlow()
                body.endControlFlow()

                if (!isVoid) body.addStatement("resValue")
            }

            XrossThreadSafety.Atomic -> {
                body.addStatement("val stamp = %L.readLock()", lockName)
                body.beginControlFlow("try")
                generateCallBody(body, method, call, isStringRet, isStructRet, returnType)
                body.nextControlFlow("finally")
                body.addStatement("%L.unlockRead(stamp)", lockName)
                body.endControlFlow()
            }

            XrossThreadSafety.Immutable -> {
                body.addStatement("val stamp = %L.writeLock()", lockName)
                body.beginControlFlow("try")
                generateCallBody(body, method, call, isStringRet, isStructRet, returnType)
                body.nextControlFlow("finally")
                body.addStatement("%L.unlockWrite(stamp)", lockName)
                body.endControlFlow()
            }
        }
    }

    private fun generateCallBody(
        body: CodeBlock.Builder,
        method: XrossMethod,
        call: String,
        isStringRet: Boolean,
        isStructRet: Boolean,
        returnType: TypeName
    ) {
        // STRUCT_SIZE は常に Companion にあるため、どこからでもアクセス可能
        val structSize = "STRUCT_SIZE"

        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                }
            }

            isStringRet -> {
                body.beginControlFlow("run")
                body.addStatement("val res = $call as %T", MemorySegment::class)
                // reinterpret の引数に reinterpret(Long.MAX_VALUE) を使うハックを維持
                body.addStatement(
                    "val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class, Long::class
                )
                body.addStatement("if (res != %T.NULL) xross_free_stringHandle.invokeExact(res)", MemorySegment::class)
                if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                body.addStatement("str")
                body.endControlFlow()
            }

            isStructRet -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement("val res = if (resRaw == %T.NULL) resRaw else resRaw.reinterpret($structSize)", MemorySegment::class)
                if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                body.addStatement("%T(res, isBorrowed = ${(method.ret as XrossType.Struct).isReference})", returnType)
                body.endControlFlow()
            }

            else -> {
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.beginControlFlow("run")
                    body.addStatement("val res = $call as %T", returnType)
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                    body.addStatement("res")
                    body.endControlFlow()
                } else {
                    body.addStatement("$call as %T", returnType)
                }
            }
        }
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
            .add("    if (raw == %T.NULL) raw else raw.reinterpret(STRUCT_SIZE)\n", MemorySegment::class)
            .add("}),\n")
            .unindent()
            .add("false")
            .build()

        builder.callThisConstructor(delegateCode)
        classBuilder.addFunction(builder.build())
    }
}
