package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    fun buildBase(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossDefinition, basePackage: String) {
        val isEnum = meta is XrossDefinition.Enum
        val isPure = XrossGenerator.isPureEnum(meta)
        
        val selfType = XrossGenerator.getClassName(meta.signature, basePackage)

        if (isPure) {
            // --- Pure Enum Case (enum class) ---
            // 内部で再代入可能にするため var に変更
            val segmentProp = PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL)
                .mutable()
                .getter(FunSpec.getterBuilder()
                    .addCode("if (field == %T.NULL) field = when(this) {\n", MemorySegment::class)
                    .apply {
                        (meta as XrossDefinition.Enum).variants.forEach { v ->
                            addCode("    %N -> new${v.name}Handle.invokeExact() as %T\n", v.name, MemorySegment::class)
                        }
                    }
                    .addCode("}\n")
                    .addCode("return field\n")
                    .build())
                .initializer("%T.NULL", MemorySegment::class)
                .build()
            classBuilder.addProperty(segmentProp)
            
            val aliveFlagClass = TypeSpec.classBuilder("AliveFlag")
                .addModifiers(KModifier.INTERNAL)
                .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("true").build())
                .build()
            classBuilder.addType(aliveFlagClass)
            classBuilder.addProperty(PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.INTERNAL)
                .initializer("AliveFlag()").build())

        } else {
            // --- Normal Struct / Complex Enum Case ---
            classBuilder.addType(
                TypeSpec.classBuilder("AliveFlag")
                    .addModifiers(KModifier.INTERNAL)
                    .primaryConstructor(FunSpec.constructorBuilder().addParameter("initial", Boolean::class).build())
                    .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("initial").build())
                    .build()
            )

            val constructorBuilder = FunSpec.constructorBuilder()
                .addModifiers(if (isEnum) KModifier.PROTECTED else KModifier.INTERNAL)
                .addParameter("raw", MemorySegment::class)
                .addParameter(ParameterSpec.builder("arena", ClassName("java.lang.foreign", "Arena")).build())
                .addParameter(ParameterSpec.builder("isArenaOwner", Boolean::class).defaultValue("true").build())
                .addParameter(ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true)).defaultValue("null").build())
            classBuilder.primaryConstructor(constructorBuilder.build())

            classBuilder.addProperty(PropertySpec.builder("arena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL).initializer("arena").build())
            classBuilder.addProperty(PropertySpec.builder("isArenaOwner", Boolean::class, KModifier.INTERNAL).mutable().initializer("isArenaOwner").build())
            classBuilder.addProperty(PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.INTERNAL).initializer(CodeBlock.of("sharedFlag ?: AliveFlag(true)")).build())
            
            // Enum の場合はサブクラスでオーバーライドできるように open var にする
            val segmentProp = PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL)
                .mutable()
                .initializer("raw")
            if (isEnum) segmentProp.addModifiers(KModifier.OPEN)
            classBuilder.addProperty(segmentProp.build())

            classBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock")).build())
        }

        // --- fromPointer メソッド ---
        if (!isEnum) {
            val fromPointerBuilder = FunSpec.builder("fromPointer")
                .addParameter("ptr", MemorySegment::class)
                .addParameter("arena", ClassName("java.lang.foreign", "Arena"))
                .addParameter(ParameterSpec.builder("isArenaOwner", Boolean::class).defaultValue("false").build())
                .returns(selfType)
                .addModifiers(KModifier.INTERNAL)
                .addCode("return %T(ptr.reinterpret(STRUCT_SIZE), arena, isArenaOwner = isArenaOwner)\n", selfType)
            
            companionBuilder.addFunction(fromPointerBuilder.build())
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        if (XrossGenerator.isPureEnum(meta)) return

        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
            .addStatement("aliveFlag.isValid = false")
            .apply {
                val hasLock = meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())
                if (hasLock) {
                    addStatement("val stamp = sl.writeLock()")
                    beginControlFlow("try")
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    beginControlFlow("if (isArenaOwner)")
                    beginControlFlow("try")
                    addStatement("arena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class)
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
                    nextControlFlow("finally")
                    addStatement("sl.unlockWrite(stamp)")
                    endControlFlow()
                } else {
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    beginControlFlow("if (isArenaOwner)")
                    beginControlFlow("try")
                    addStatement("arena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class)
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
                }
            }
            .endControlFlow()

        classBuilder.addFunction(FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addCode(closeBody.build()).build())
    }
}