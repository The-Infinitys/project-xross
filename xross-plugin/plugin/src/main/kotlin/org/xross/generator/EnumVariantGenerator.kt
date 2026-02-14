package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object EnumVariantGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    private fun addVariantFactoryMethod(
        companionBuilder: TypeSpec.Builder,
        factoryMethodName: String,
        tripleType: TypeName,
        basePackage: String,
        handleCall: CodeBlock,
        fields: List<XrossField> = emptyList(),
    ) {
        companionBuilder.addFunction(
            FunSpec.builder(factoryMethodName)
                .addModifiers(KModifier.PRIVATE)
                .addParameters(
                    fields.map { field ->
                        val kType = if (field.ty is XrossType.Object) {
                            GeneratorUtils.getClassName(field.ty.signature, basePackage)
                        } else {
                            field.ty.kotlinType
                        }
                        ParameterSpec.builder("argOf" + field.name.toCamelCase(), kType).build()
                    },
                )
                .returns(tripleType)
                .addCode(
                    CodeBlock.builder().apply {
                        addFactoryBody(
                            basePackage,
                            handleCall,
                            CodeBlock.of("STRUCT_SIZE"),
                            CodeBlock.of("dropHandle"),
                        )
                        fields.forEach { field ->
                            if (field.ty is XrossType.Object && field.ty.isOwned) {
                                val argName = "argOf" + field.name.toCamelCase()
                                addStatement("$argName.relinquish()")
                            }
                        }
                        addStatement("return %T(res, %T(newAutoArena, newOwnerArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())
                    }.build(),
                )
                .build(),
        )
    }

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String,
        basePackage: String,
    ) {
        val isPure = GeneratorUtils.isPureEnum(meta)
        val baseClassName = ClassName(targetPackage, meta.name)
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)

        val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", baseClassName, basePackage)

        if (isPure) {
            meta.variants.forEach { variant ->
                classBuilder.addEnumConstant(variant.name)
            }
            fromPointerBuilder.addCode(
                CodeBlock.builder()
                    .beginControlFlow("try")
                    .addStatement("if (ptr == %T.NULL) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL")
                    .addRustStringResolution("getVariantNameHandle.invokeExact(ptr)", "name")
                    .addStatement("return valueOf(name)")
                    .nextControlFlow("finally")
                    .add("// Manual close is removed because we use Arena.ofAuto() for safety\n")
                    .endControlFlow()
                    .build(),
            )
        } else {
            fromPointerBuilder.addCode(
                CodeBlock.builder()
                    .beginControlFlow("val name = run")
                    .addStatement("if (ptr == %T.NULL) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL")
                    .addRustStringResolution("getVariantNameHandle.invokeExact(ptr)", "n")
                    .addStatement("n")
                    .endControlFlow()
                    .build(),
            )

            fromPointerBuilder.addStatement("val reinterpretedPtr = if (ptr.byteSize() >= STRUCT_SIZE) ptr else ptr.reinterpret(STRUCT_SIZE)")
            fromPointerBuilder.beginControlFlow("return when (name)")

            meta.variants.forEach { variant ->
                val variantClassName = baseClassName.nestedClass(variant.name)
                val isObject = variant.fields.isEmpty()

                val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                    .superclass(baseClassName)

                GeneratorUtils.buildRawInitializer(variantTypeBuilder, aliveFlagType)
                GeneratorUtils.addInternalConstructor(variantTypeBuilder, tripleType)

                if (isObject) {
                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    addVariantFactoryMethod(
                        companionBuilder,
                        factoryMethodName,
                        tripleType,
                        basePackage,
                        CodeBlock.of("new${variant.name}Handle.invokeExact()"),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .callThisConstructor(CodeBlock.of("$factoryMethodName()"))
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.builder("equals")
                            .addModifiers(KModifier.OVERRIDE)
                            .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                            .returns(Boolean::class)
                            .addStatement("return other is %T", variantClassName)
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.builder("hashCode")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(Int::class)
                            .addStatement("return %S.hashCode()", variant.name)
                            .build(),
                    )
                } else {
                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    addVariantFactoryMethod(
                        companionBuilder,
                        factoryMethodName,
                        tripleType,
                        basePackage,
                        CodeBlock.of(
                            "new${variant.name}Handle.invokeExact(${variant.fields.joinToString(", ") { field ->
                                val argName = "argOf" + field.name.toCamelCase()
                                when (field.ty) {
                                    is XrossType.Bool -> {
                                        "if ($argName) 1.toByte() else 0.toByte()"
                                    }

                                    is XrossType.Object -> {
                                        "$argName.segment"
                                    }

                                    else -> {
                                        argName
                                    }
                                }
                            }})",
                        ),
                        variant.fields,
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .addParameters(
                                variant.fields.map { field ->
                                    val kType = if (field.ty is XrossType.Object) {
                                        GeneratorUtils.getClassName(field.ty.signature, basePackage)
                                    } else {
                                        field.ty.kotlinType
                                    }
                                    ParameterSpec.builder(field.name.toCamelCase().escapeKotlinKeyword(), kType).build()
                                },
                            )
                            .callThisConstructor(CodeBlock.of("$factoryMethodName(${variant.fields.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }})"))
                            .build(),
                    )

                    val equalsBuilder = FunSpec.builder("equals")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                        .returns(Boolean::class)
                        .addStatement("if (this === other) return true")
                        .addStatement("if (other !is %T) return false", variantClassName)

                    variant.fields.forEach { field ->
                        val baseCamelName = field.name.toCamelCase()
                        val isPrimitive = field.ty !is XrossType.Object && field.ty !is XrossType.Optional && field.ty !is XrossType.Result
                        val combinedName = "${variant.name}_$baseCamelName"
                        val vhName = if (isPrimitive) "VH_$combinedName" else "null"
                        val offsetName = "OFFSET_$combinedName"
                        val kType = if (field.ty is XrossType.Object) {
                            GeneratorUtils.getClassName(field.ty.signature, basePackage)
                        } else {
                            field.ty.kotlinType
                        }

                        val backingFieldName = GeneratorUtils.addBackingPropertyIfNeeded(variantTypeBuilder, field, baseCamelName, kType)

                        variantTypeBuilder.addProperty(
                            PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                                .mutable(field.safety != XrossThreadSafety.Immutable)
                                .getter(GeneratorUtils.buildFullGetter(kType, buildVariantGetterBody(variant.name, field, vhName, offsetName, kType, baseClassName, backingFieldName, basePackage)))
                                .apply {
                                    if (field.safety != XrossThreadSafety.Immutable) {
                                        setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildVariantSetterBody(variant.name, field, vhName, offsetName, kType, backingFieldName)))
                                    }
                                }
                                .build(),
                        )

                        equalsBuilder.addStatement("if (this.%L != other.%L) return false", baseCamelName.escapeKotlinKeyword(), baseCamelName.escapeKotlinKeyword())
                    }

                    equalsBuilder.addStatement("return true")
                    variantTypeBuilder.addFunction(equalsBuilder.build())
                }

                classBuilder.addType(variantTypeBuilder.build())
                fromPointerBuilder.addStatement("%S -> %T(reinterpretedPtr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)", variant.name, variantClassName)
            }

            fromPointerBuilder.addStatement("else -> throw %T(%S + name)", RuntimeException::class.asTypeName(), "Unknown variant: ")
            fromPointerBuilder.endControlFlow()
        }

        companionBuilder.addFunction(fromPointerBuilder.build())
    }

    private fun buildVariantGetterBody(variantName: String, field: XrossField, vhName: String, offsetName: String, kType: TypeName, selfType: ClassName, backingFieldName: String?, basePackage: String): CodeBlock {
        val baseCamel = field.name.toCamelCase()
        val combinedName = "${variantName}_$baseCamel"
        return FieldBodyGenerator.buildGetterBody(
            field,
            vhName,
            offsetName,
            kType,
            selfType,
            backingFieldName,
            basePackage,
        ) { ty ->
            when (ty) {
                is XrossType.Optional -> "${combinedName}OptGetHandle"
                is XrossType.Result -> "${combinedName}ResGetHandle"
                is XrossType.RustString -> "${combinedName}StrGetHandle"
                else -> ""
            }
        }
    }

    private fun buildVariantSetterBody(variantName: String, field: XrossField, vhName: String, offsetName: String, kType: TypeName, backingFieldName: String?): CodeBlock {
        val baseCamel = field.name.toCamelCase()
        val combinedName = "${variantName}_$baseCamel"
        return FieldBodyGenerator.buildSetterBody(
            field,
            vhName,
            offsetName,
            kType,
            ClassName("", "UNUSED"),
            backingFieldName,
        ) { ty ->
            when (ty) {
                is XrossType.Optional -> "${combinedName}OptSetHandle"
                is XrossType.Result -> "${combinedName}ResSetHandle"
                is XrossType.RustString -> "${combinedName}StrSetHandle"
                else -> ""
            }
        }
    }
}
