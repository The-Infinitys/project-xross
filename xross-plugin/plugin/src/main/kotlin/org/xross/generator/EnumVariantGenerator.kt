package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment
import java.lang.foreign.Arena

object EnumVariantGenerator {

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String,
        basePackage: String
    ) {
        val isPure = XrossGenerator.isPureEnum(meta)
        val baseClassName = ClassName(targetPackage, meta.name)
        val pairType = Pair::class.asClassName().parameterizedBy(MemorySegment::class.asClassName(), Arena::class.asClassName())

        val fromPointerBuilder = FunSpec.builder("fromPointer")
            .addParameter("ptr", MemorySegment::class)
            .addParameter("arena", Arena::class)
            .addParameter(ParameterSpec.builder("isArenaOwner", Boolean::class).defaultValue("false").build())
            .returns(baseClassName)
            .addModifiers(KModifier.INTERNAL)

        if (isPure) {
            // --- Pure Enum Case (enum class) ---
            meta.variants.forEach { variant ->
                classBuilder.addEnumConstant(variant.name)
            }

            fromPointerBuilder.addCode(
                """
                try {
                    if (ptr == %T.NULL) throw %T("Pointer is NULL")
                    val nameRaw = getVariantNameHandle.invokeExact(ptr) as %T
                    val name = if (nameRaw == %T.NULL) "" else nameRaw.reinterpret(%T.MAX_VALUE).getString(0)
                    if (nameRaw != %T.NULL) xrossFreeStringHandle.invokeExact(nameRaw)
                    return valueOf(name)
                } finally {
                    if (isArenaOwner) {
                        arena.close()
                    }
                }
                """.trimIndent(),
                MemorySegment::class, NullPointerException::class, MemorySegment::class, MemorySegment::class, Long::class, MemorySegment::class
            )
        } else {
            // --- Complex Enum Case (sealed class) ---
            fromPointerBuilder.addCode(
                """
                if (ptr == %T.NULL) throw %T("Pointer is NULL")
                val nameRaw = getVariantNameHandle.invokeExact(ptr) as %T
                val name = if (nameRaw == %T.NULL) "" else nameRaw.reinterpret(%T.MAX_VALUE).getString(0)
                if (nameRaw != %T.NULL) xrossFreeStringHandle.invokeExact(nameRaw)
                return when (name) {
                """.trimIndent(),
                MemorySegment::class, NullPointerException::class, MemorySegment::class, MemorySegment::class, Long::class, MemorySegment::class
            )

            meta.variants.forEach { variant ->
                val variantClassName = baseClassName.nestedClass(variant.name)
                val isObject = variant.fields.isEmpty()

                if (isObject) {
                    // data object variant (ユニットバリアント)
                    val objectBuilder = TypeSpec.objectBuilder(variant.name)
                        .addModifiers(KModifier.DATA)
                        .superclass(baseClassName)
                        .addSuperclassConstructorParameter("%T.NULL", MemorySegment::class)
                        .addSuperclassConstructorParameter("%T.global()", Arena::class)
                        .addSuperclassConstructorParameter("false")
                    
                    val segmentProp = PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL, KModifier.OVERRIDE)
                        .mutable()
                        .getter(FunSpec.getterBuilder()
                            .addCode("if (field == %T.NULL) field = new${variant.name}Handle.invokeExact() as %T\n", MemorySegment::class, MemorySegment::class)
                            .addCode("return field\n")
                            .build())
                        .initializer("%T.NULL", MemorySegment::class)
                        .build()
                    objectBuilder.addProperty(segmentProp)

                    classBuilder.addType(objectBuilder.build())

                    fromPointerBuilder.addCode("    %S -> {\n", variant.name)
                    fromPointerBuilder.addCode("        if (isArenaOwner) arena.close()\n")
                    fromPointerBuilder.addCode("        %T\n", variantClassName)
                    fromPointerBuilder.addCode("    }\n")
                } else {
                    // class variant
                    fromPointerBuilder.addCode("    %S -> %T(ptr.reinterpret(STRUCT_SIZE), arena, isArenaOwner = isArenaOwner)\n", variant.name, variantClassName)

                    val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                    variantTypeBuilder.superclass(baseClassName)

                    variantTypeBuilder.primaryConstructor(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.INTERNAL)
                        .addParameter("raw", MemorySegment::class)
                        .addParameter("arena", Arena::class)
                        .addParameter(ParameterSpec.builder("isArenaOwner", Boolean::class).defaultValue("true").build())
                        .build())
                    variantTypeBuilder.addSuperclassConstructorParameter("raw")
                    variantTypeBuilder.addSuperclassConstructorParameter("arena")
                    variantTypeBuilder.addSuperclassConstructorParameter("isArenaOwner")

                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    val callInvokeArgs = variant.fields.joinToString(", ") { field ->
                        val argName = "arg_" + field.name.toCamelCase()
                        when (field.ty) {
                            is XrossType.Object -> "$argName.segment"
                            is XrossType.Bool -> "if ($argName) 1.toByte() else 0.toByte()"
                            else -> argName
                        }
                    }

                    val factoryBody = CodeBlock.builder()
                        .addStatement("val newArena = Arena.ofAuto()")
                        .addStatement("val resRaw = new${variant.name}Handle.invokeExact($callInvokeArgs) as %T", MemorySegment::class)
                        .addStatement("val res = resRaw.reinterpret(STRUCT_SIZE, newArena) { s -> dropHandle.invokeExact(s) }")
                        .addStatement("return res to newArena")

                    companionBuilder.addFunction(FunSpec.builder(factoryMethodName)
                        .addModifiers(KModifier.PRIVATE)
                        .addParameters(variant.fields.map { field ->
                            val kType = if (field.ty is XrossType.Object) {
                                XrossGenerator.getClassName(field.ty.signature, basePackage)
                            } else {
                                field.ty.kotlinType
                            }
                            ParameterSpec.builder("arg_" + field.name.toCamelCase(), kType).build()
                        })
                        .returns(pairType)
                        .addCode(factoryBody.build())
                        .build())

                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.PRIVATE)
                        .addParameter("p", pairType)
                        .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second"), CodeBlock.of("true"))
                        .build())
                    
                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .addParameters(variant.fields.map { field ->
                            val kType = if (field.ty is XrossType.Object) {
                                XrossGenerator.getClassName(field.ty.signature, basePackage)
                            } else {
                                field.ty.kotlinType
                            }
                            ParameterSpec.builder(field.name.toCamelCase().escapeKotlinKeyword(), kType).build()
                        })
                        .callThisConstructor(CodeBlock.of("$factoryMethodName(${variant.fields.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }})"))
                        .build())

                    val equalsBuilder = FunSpec.builder("equals")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                        .returns(Boolean::class)
                        .addStatement("if (this === other) return true")
                        .addStatement("if (other !is %T) return false", variantClassName)
                    
                    val hashCodeBuilder = FunSpec.builder("hashCode")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(Int::class)
                        .addCode("var result = %T::class.hashCode()\n", variantClassName)

                    variant.fields.forEach { field ->
                        val baseCamelName = field.name.toCamelCase()
                        val vhName = "VH_${variant.name}_$baseCamelName"
                        val offsetName = "OFFSET_${variant.name}_$baseCamelName"
                        val kType = if (field.ty is XrossType.Object) {
                            XrossGenerator.getClassName(field.ty.signature, basePackage)
                        } else {
                            field.ty.kotlinType
                        }

                        if (field.safety == XrossThreadSafety.Atomic) {
                            generateAtomicPropertyInVariant(variantTypeBuilder, baseCamelName, vhName, kType, variant.name)
                        } else {
                            val isMutable = field.safety != XrossThreadSafety.Immutable
                            val propBuilder = PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                                .mutable(isMutable)
                                .getter(buildVariantGetter(field, vhName, offsetName, kType, baseClassName))
                            if (isMutable) propBuilder.setter(buildVariantSetter(field, vhName, kType))
                            variantTypeBuilder.addProperty(propBuilder.build())
                        }
                        
                        equalsBuilder.addStatement("if (this.%L != other.%L) return false", baseCamelName.escapeKotlinKeyword(), baseCamelName.escapeKotlinKeyword())
                        hashCodeBuilder.addStatement("result = 31 * result + %L.hashCode()", baseCamelName.escapeKotlinKeyword())
                    }
                    
                    equalsBuilder.addStatement("return true")
                    variantTypeBuilder.addFunction(equalsBuilder.build())
                    hashCodeBuilder.addStatement("return result")
                    variantTypeBuilder.addFunction(hashCodeBuilder.build())

                    classBuilder.addType(variantTypeBuilder.build())
                }
            }

            fromPointerBuilder.addCode("    else -> throw %T(%S + name)\n", RuntimeException::class, "Unknown variant: ")
            fromPointerBuilder.addCode("}")
        }

        companionBuilder.addFunction(fromPointerBuilder.build())
    }

    private fun buildVariantGetter(field: XrossField, vhName: String, offsetName: String, kType: TypeName, selfType: ClassName): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Attempted to access field '${field.name}' on a NULL or invalid native object")

        val isSelf = kType == selfType

        when (field.ty) {
            is XrossType.Bool -> readCodeBuilder.addStatement("res = ($vhName.get(this.segment, 0L) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement("val rawSegment = $vhName.get(this.segment, 0L) as %T", MemorySegment::class)
                readCodeBuilder.addStatement("res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)", MemorySegment::class, Long::class)
            }
            is XrossType.Object -> {
                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    readCodeBuilder.addStatement(
                        "val resSeg = this.segment.asSlice($offsetName, $sizeExpr)",
                        *(if (isSelf) emptyArray() else arrayOf(kType))
                    )
                    val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                    readCodeBuilder.addStatement(
                        "res = $fromPointerExpr(resSeg, this.arena)",
                        *(if (isSelf) emptyArray() else arrayOf(kType))
                    )
                } else {
                    readCodeBuilder.addStatement("val rawSegment = $vhName.get(this.segment, 0L) as %T", MemorySegment::class)
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                    
                    if (field.ty.ownership == XrossType.Ownership.Boxed) {
                        readCodeBuilder.addStatement("val retArena = Arena.ofConfined()")
                        val dropExpr = if (isSelf) "dropHandle" else "%T.dropHandle"
                        readCodeBuilder.addStatement(
                            "val resSeg = rawSegment.reinterpret($sizeExpr, retArena) { s -> $dropExpr.invokeExact(s) }",
                            *(if (isSelf) emptyArray() else arrayOf(kType, kType))
                        )
                        readCodeBuilder.addStatement(
                            "res = $fromPointerExpr(resSeg, retArena, isArenaOwner = true)",
                            *(if (isSelf) emptyArray() else arrayOf(kType))
                        )
                    } else {
                        readCodeBuilder.addStatement(
                            "val resSeg = if (rawSegment == %T.NULL) %T.NULL else rawSegment.reinterpret($sizeExpr)",
                            *(if (isSelf) arrayOf(MemorySegment::class, MemorySegment::class) else arrayOf(MemorySegment::class, MemorySegment::class, kType))
                        )
                        readCodeBuilder.addStatement(
                            "res = $fromPointerExpr(resSeg, this.arena)",
                            *(if (isSelf) emptyArray() else arrayOf(kType))
                        )
                    }
                }
            }
            else -> readCodeBuilder.addStatement("res = $vhName.get(this.segment, 0L) as %T", kType)
        }

        return FunSpec.getterBuilder().addCode(
            """
            var stamp = this.sl.tryOptimisticRead()
            var res: %T
            // Optimistic read
            %L
            if (!this.sl.validate(stamp)) {
                stamp = this.sl.readLock()
                try { 
                    // Pessimistic read
                    %L
                } finally { this.sl.unlockRead(stamp) }
            }
            return res
        """.trimIndent(), kType, readCodeBuilder.build(), readCodeBuilder.build()
        ).build()
    }

    private fun buildVariantSetter(field: XrossField, vhName: String, kType: TypeName): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Attempted to set field '${field.name}' on a NULL or invalid native object")

        when (field.ty) {
            is XrossType.Bool -> writeCodeBuilder.addStatement("$vhName.set(this.segment, 0L, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Cannot set field '${field.name}' with a NULL or invalid native reference")
                writeCodeBuilder.addStatement("$vhName.set(this.segment, 0L, v.segment)")
            }
            else -> writeCodeBuilder.addStatement("$vhName.set(this.segment, 0L, v)")
        }

        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
            """
            val stamp = this.sl.writeLock()
            try { %L } finally { this.sl.unlockWrite(stamp) }
        """.trimIndent(), writeCodeBuilder.build()
        ).build()
    }

    private fun generateAtomicPropertyInVariant(builder: TypeSpec.Builder, baseName: String, vhName: String, kType: TypeName, variantName: String) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(PropertySpec.builder("value", kType)
                .getter(FunSpec.getterBuilder().addStatement("return $vhName.getVolatile(this@${variantName}.segment, 0L) as %T", kType).build()).build())
            .addFunction(FunSpec.builder("update")
                .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                .beginControlFlow("while (true)")
                .beginControlFlow("try")
                .addStatement("val current = value")
                .addStatement("val next = block(current)")
                .beginControlFlow("if ($vhName.compareAndSet(this@${variantName}.segment, 0L, current, next))")
                .addStatement("return next")
                .endControlFlow()
                .nextControlFlow("catch (e: %T)", Throwable::class)
                .addStatement("throw e")
                .endControlFlow()
                .endControlFlow().build())
            .build()
        builder.addType(innerClass)
        builder.addProperty(PropertySpec.builder(baseName.escapeKotlinKeyword(), ClassName("", innerClassName)).initializer("%L()", innerClassName).build())
    }
}