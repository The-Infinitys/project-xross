package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment

object PropertyGenerator {

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, basePackage: String) {
        val selfType = XrossGenerator.getClassName(meta.signature, basePackage)
        
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val kType = if (field.ty is XrossType.Object) {
                XrossGenerator.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            var backingFieldName: String? = null
            if (field.ty is XrossType.Object) {
                backingFieldName = "_$baseName"
                val backingProp = PropertySpec.builder(backingFieldName, kType.copy(nullable = true))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("null")
                    .build()
                classBuilder.addProperty(backingProp)
            }

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, kType)
            } else {
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(buildGetter(field, vhName, kType, backingFieldName, selfType)) 

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, kType, backingFieldName))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val isSelf = kType == selfType

        when (field.ty) {
            is XrossType.Object -> {
                readCodeBuilder.beginControlFlow("if (this.$backingFieldName != null && this.$backingFieldName!!.aliveFlag.isValid)")
                readCodeBuilder.addStatement("res = this.$backingFieldName!!")
                readCodeBuilder.nextControlFlow("else")

                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    readCodeBuilder.addStatement("val offset = LAYOUT.byteOffset(%T.PathElement.groupElement(%S))", MemoryLayout::class, field.name)
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    readCodeBuilder.addStatement(
                        "val resSeg = this.segment.asSlice(offset, $sizeExpr)",
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
                    readCodeBuilder.addStatement(
                        "val resSeg = if (rawSegment == %T.NULL) %T.NULL else rawSegment.reinterpret($sizeExpr)",
                        *(if (isSelf) arrayOf(MemorySegment::class, MemorySegment::class) else arrayOf(MemorySegment::class, MemorySegment::class, kType))
                    )
                    val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                    readCodeBuilder.addStatement(
                        "res = $fromPointerExpr(resSeg, this.arena)",
                        *(if (isSelf) emptyArray() else arrayOf(kType))
                    )
                }
                readCodeBuilder.addStatement("this.$backingFieldName = res")
                readCodeBuilder.endControlFlow()
            }
            is XrossType.Bool -> readCodeBuilder.addStatement("res = ($vhName.get(this.segment, 0L) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement(
                    "val rawSegment = $vhName.get(this.segment, 0L) as %T",
                    MemorySegment::class
                )
                readCodeBuilder.addStatement(
                    "res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
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
    private fun buildSetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement(
            "if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)",
            MemorySegment::class,
            NullPointerException::class,
            "Attempted to set field '${field.name}' on a NULL or invalid native object"
        )

        when (field.ty) {
            is XrossType.Bool -> writeCodeBuilder.addStatement("$vhName.set(this.segment, 0L, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement(
                    "if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Cannot set field '${field.name}' with a NULL or invalid native reference"
                )
                writeCodeBuilder.addStatement("$vhName.set(this.segment, 0L, v.segment)")

                if (backingFieldName != null) {
                    writeCodeBuilder.addStatement("this.$backingFieldName = null")
                }
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
    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        baseName: String,
        escapedName: String,
        vhName: String,
        kType: TypeName
    ) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return $vhName.getVolatile(this@${className(classBuilder)}.segment, 0L) as %T", kType)
                            .build()
                    ).build()
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                    .beginControlFlow("while (true)")
                    .beginControlFlow("try")
                    .addStatement("val current = value")
                    .addStatement("val next = block(current)")
                    .beginControlFlow("if ($vhName.compareAndSet(this@${className(classBuilder)}.segment, 0L, current, next))")
                    .addStatement("return next")
                    .endControlFlow()
                    .nextControlFlow("catch (e: %T)", Throwable::class)
                    .addStatement("throw e")
                    .endControlFlow()
                    .endControlFlow().build()
            )
            .build()
        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName)).initializer("%L()", innerClassName).build()
        )
    }

    private fun className(builder: TypeSpec.Builder): String = builder.build().name!!
}