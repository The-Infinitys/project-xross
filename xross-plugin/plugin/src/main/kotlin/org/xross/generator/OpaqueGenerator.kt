package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import org.xross.generator.util.addArgumentPreparation
import org.xross.generator.util.addRustStringResolution
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object OpaqueGenerator {

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Opaque, basePackage: String) {
        // Add fields for Opaque
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val kType = if (field.ty is XrossType.Object) {
                GeneratorUtils.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            val propBuilder = PropertySpec.builder(escapedName, kType)
                .mutable(true) // External fields are assumed mutable
                .getter(buildOpaqueGetter(field, kType, basePackage))
                .setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildOpaqueSetterBody(field, basePackage), useAsyncLock = false))
            classBuilder.addProperty(propBuilder.build())
        }
    }

    private fun buildOpaqueGetter(field: XrossField, kType: TypeName, basePackage: String): FunSpec {
        val baseName = field.name.toCamelCase()
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val getHandle = when (field.ty) {
            is XrossType.RustString -> "${baseName}StrGetHandle"
            is XrossType.Optional -> "${baseName}OptGetHandle"
            is XrossType.Result -> "${baseName}ResGetHandle"
            else -> "${baseName}GetHandle"
        }

        when (field.ty) {
            is XrossType.Result -> {
                body.addStatement("val resRaw = $getHandle.invokeExact(this.segment) as %T", MemorySegment::class)
                body.addStatement(
                    "val isOk = resRaw.get(%M, 0L) != (0).toByte()",
                    MemberName("java.lang.foreign.ValueLayout", "JAVA_BYTE"),
                )
                body.addStatement("val ptr = resRaw.get(%M, 8L)", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"))
                body.beginControlFlow("val res = if (isOk)")
                body.addStatement("Result.success(ptr) // TODO: Full resolution if needed")
                body.nextControlFlow("else")
                body.addStatement("Result.failure(%T(ptr))", ClassName("$basePackage.xross.runtime", "XrossException"))
                body.endControlFlow()
                body.addStatement("return res as %T", kType)
            }

            is XrossType.RustString -> {
                body.addRustStringResolution("$getHandle.invokeExact(this.arena as java.lang.foreign.SegmentAllocator, this.segment)", "s", basePackage = basePackage)
                body.addStatement("return s")
            }

            else -> {
                body.addStatement("return $getHandle.invokeExact(this.segment) as %T", kType)
            }
        }

        return FunSpec.getterBuilder().addCode(body.build()).build()
    }

    private fun buildOpaqueSetterBody(field: XrossField, basePackage: String): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Object invalid")

        val setHandle = when (field.ty) {
            is XrossType.RustString -> "${field.name.toCamelCase()}StrSetHandle"
            is XrossType.Optional -> "${field.name.toCamelCase()}OptSetHandle"
            else -> "${field.name.toCamelCase()}SetHandle"
        }

        body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
        val callArgs = mutableListOf<CodeBlock>()
        body.addArgumentPreparation(field.ty, "v", callArgs, basePackage = basePackage)
        body.addStatement("$setHandle.invoke(this.segment, ${callArgs.joinToString(", ")})")
        body.endControlFlow()

        return body.build()
    }
}
