package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.XrossClass
import org.xross.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    fun buildBase(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        // FieldMemoryInfo の定義 (変更なし)
        classBuilder.addType(
            TypeSpec.classBuilder("FieldMemoryInfo")
                .addModifiers(KModifier.PRIVATE, KModifier.DATA)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("offset", Long::class)
                        .addParameter("size", Long::class).build()
                )
                .addProperty(PropertySpec.builder("offset", Long::class).initializer("offset").build())
                .addProperty(PropertySpec.builder("size", Long::class).initializer("size").build())
                .build()
        )

        // プライマリコンストラクタ: 引数をそのままプロパティとして宣言する
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("raw", MemorySegment::class)
                .addParameter(ParameterSpec.builder("isBorrowed", Boolean::class).defaultValue("false").build())
                .build()
        )

        // セグメントプロパティ: コンストラクタ引数 'raw' を受け取る
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE)
                .mutable()
                .initializer("raw")
                .build()
        )

        // 借用フラグプロパティ: コンストラクタ引数 'isBorrowed' を受け取る
        classBuilder.addProperty(
            PropertySpec.builder("isBorrowed", Boolean::class, KModifier.PRIVATE)
                .initializer("isBorrowed")
                .build()
        )

        // Lock の生成 (インスタンス用)
        if (meta.methods.any { it.methodType != XrossMethodType.Static } || meta.fields.isNotEmpty()) {
            classBuilder.addProperty(
                PropertySpec.builder("lock", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("ReentrantReadWriteLock()")
                    .build()
            )
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val deallocatorName = "Deallocator"
        val handleType = ClassName("java.lang.invoke", "MethodHandle")

        classBuilder.addType(buildDeallocator(handleType))

        // cleanable は null を許容するように型を変更 (Cleanable?)
        classBuilder.addProperty(
            PropertySpec.builder("cleanable", ClassName("java.lang.ref.Cleaner", "Cleanable").copy(nullable = true), KModifier.PRIVATE)
                .mutable()
                .initializer("if (isBorrowed) null else CLEANER.register(this, %L(segment, dropHandle))", deallocatorName)
                .build()
        )

        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
            .apply {
                val hasLock = meta.methods.any { it.methodType != XrossMethodType.Static } || meta.fields.isNotEmpty()
                if (hasLock) beginControlFlow("lock.writeLock().withLock")

                // セーフコールで clean() を呼ぶ
                addStatement("cleanable?.clean()")
                addStatement("segment = %T.NULL", MemorySegment::class)

                if (hasLock) endControlFlow()
            }
            .endControlFlow()

        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(closeBody.build()).build()
        )
    }

    private fun buildDeallocator(handleType: TypeName) = TypeSpec.classBuilder("Deallocator")
        .addModifiers(KModifier.PRIVATE)
        .addSuperinterface(Runnable::class)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("segment", MemorySegment::class)
                .addParameter("dropHandle", handleType).build()
        )
        .addProperty(PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE).initializer("segment").build())
        .addProperty(PropertySpec.builder("dropHandle", handleType, KModifier.PRIVATE).initializer("dropHandle").build())
        .addFunction(
            FunSpec.builder("run")
                .addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
                .beginControlFlow("try")
                .addStatement("dropHandle.invokeExact(segment)")
                .nextControlFlow("catch (e: Throwable)")
                .addStatement("System.err.println(%S + e.message)", "Xross: Failed to drop native object: ")
                .endControlFlow()
                .endControlFlow()
                .build()
        )
        .build()
}
