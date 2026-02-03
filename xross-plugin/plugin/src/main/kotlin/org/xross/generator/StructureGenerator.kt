package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    fun buildBase(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        // --- AliveFlag: 生存確認フラグ ---
        classBuilder.addType(
            TypeSpec.classBuilder("AliveFlag")
                .addModifiers(KModifier.PRIVATE)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("initial", Boolean::class).build())
                .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("initial").build())
                .build()
        )

        // --- コンストラクタの設定 ---
        val constructorBuilder = FunSpec.constructorBuilder()
            .addModifiers(KModifier.PRIVATE)
            .addParameter("raw", MemorySegment::class)
            .addParameter(ParameterSpec.builder("isBorrowed", Boolean::class).defaultValue("false").build())
            .addParameter(
                ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true))
                    .defaultValue("null").build()
            )

        // Enum Class (フィールドなし) の場合は、引数なしコンストラクタにするため調整が必要
        if (meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }) {
            // Enum class の各要素は自身のコンパニオン等からセグメントを取得するため、
            // デフォルト値を設定するか、初期化ロジックを init に逃がす
            classBuilder.primaryConstructor(constructorBuilder.build())
        } else {
            classBuilder.primaryConstructor(constructorBuilder.build())
        }

        // --- プロパティの定義 ---
        classBuilder.addProperty(
            PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.PRIVATE)
                .initializer("sharedFlag ?: AliveFlag(true)")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PROTECTED)
                .mutable()
                .initializer("raw")
                .build()
        )

        // --- StampedLock (sl) の定義 ---
        // Enum でもメソッド呼び出しがある場合はロックが必要
        if (meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())) {
            classBuilder.addProperty(
                PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .build()
            )
        }

        // --- Enum class 専用の初期化 (各定数へのポインタ割り当て) ---
        if (meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }) {
            // Simple Enum の場合、各定数に対応する Rust インスタンスを init で生成
            val initBlock = CodeBlock.builder()
                .beginControlFlow("if (raw == %T.NULL)", MemorySegment::class)
                .beginControlFlow("segment = when (this.name)")
                .apply {
                    meta.variants.forEach { variant ->
                        addStatement("%S -> %L_new_%L.invokeExact() as %T", variant.name, meta.symbolPrefix, variant.name, MemorySegment::class)
                    }
                    addStatement("else -> %T.NULL", MemorySegment::class)
                }
                .endControlFlow()
                .endControlFlow()
            classBuilder.addInitializerBlock(initBlock.build())
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        val deallocatorName = "Deallocator"
        val handleType = ClassName("java.lang.invoke", "MethodHandle")
        classBuilder.addType(buildDeallocator(handleType))

        classBuilder.addProperty(
            PropertySpec.builder(
                "cleanable",
                ClassName("java.lang.ref.Cleaner", "Cleanable").copy(nullable = true),
                KModifier.PRIVATE
            )
                .mutable()
                .initializer(
                    "if (isBorrowed || segment == %T.NULL) null else CLEANER.register(this, %L(segment, dropHandle))",
                    MemorySegment::class, deallocatorName
                )
                .build()
        )

        // close メソッドの実装
        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
            .addStatement("aliveFlag.isValid = false")
            .apply {
                val hasLock = meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())
                if (hasLock) {
                    addStatement("val stamp = sl.writeLock()")
                    beginControlFlow("try")
                    addStatement("cleanable?.clean()")
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    nextControlFlow("finally")
                    addStatement("sl.unlockWrite(stamp)")
                    endControlFlow()
                } else {
                    addStatement("cleanable?.clean()")
                    addStatement("segment = %T.NULL", MemorySegment::class)
                }
            }
            .endControlFlow()

        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(closeBody.build())
                .build()
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
        .addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE).initializer("segment").build()
        )
        .addProperty(
            PropertySpec.builder("dropHandle", handleType, KModifier.PRIVATE).initializer("dropHandle").build()
        )
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
