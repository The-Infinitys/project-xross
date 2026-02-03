package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment

object PropertyGenerator {
    private val BYTE = ClassName("kotlin", "Byte")

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        // --- 修正箇所: Enum の場合は何もしない ---
        if (meta !is XrossDefinition.Struct) return

        meta.fields.forEach { field ->
            val baseCamelName = field.name.toCamelCase()
            val escapedName = baseCamelName.escapeKotlinKeyword()
            val vhName = "VH_$baseCamelName"

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, field, baseCamelName, escapedName, vhName)
            } else {
                val propBuilder = PropertySpec.builder(escapedName, field.ty.kotlinType)

                if (field.safety == XrossThreadSafety.Immutable) {
                    propBuilder.getter(buildGetter(field, vhName, isLocked = false))
                } else {
                    propBuilder.mutable(true)
                    // Lock が指定されている場合は StampedLock を使用
                    val useLock = field.safety == XrossThreadSafety.Lock
                    propBuilder.getter(buildGetter(field, vhName, isLocked = useLock))
                    propBuilder.setter(buildSetter(field, vhName, isLocked = useLock))
                }
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }

    /**
     * Getter の生成。Lock が有効な場合は StampedLock を用いた楽観的読み取りを行う。
     */
    private fun buildGetter(field: XrossField, vhName: String, isLocked: Boolean): FunSpec {
        val getterBuilder = FunSpec.getterBuilder()
        val type = field.ty
        val kType = type.kotlinType

        val rawReadExpr = when (type) {
            is XrossType.Bool -> "($vhName.get(segment, 0L) as %T) != (0).toByte()"
            is XrossType.RustString -> {
                "(($vhName.get(segment, 0L) as %T).let { if (it == %T.NULL) it else it.reinterpret(%T.MAX_VALUE) })"
            }
            is XrossType.RustStruct, is XrossType.RustEnum -> {
                // Struct/Enum の場合は MemorySegment から Kotlin オブジェクトへラップ
                "%T($vhName.get(segment, 0L) as %T, isBorrowed = true)"
            }
            else -> "$vhName.get(segment, 0L) as %T"
        }

        val args = when (type) {
            is XrossType.Bool -> arrayOf(BYTE)
            is XrossType.RustString -> arrayOf(MemorySegment::class, MemorySegment::class, Long::class)
            is XrossType.RustStruct, is XrossType.RustEnum -> arrayOf(kType, MemorySegment::class)
            else -> arrayOf(kType)
        }

        if (!isLocked) {
            getterBuilder.addCode("return $rawReadExpr", *args)
        } else {
            getterBuilder.apply {
                addStatement("var stamp = sl.tryOptimisticRead()")
                addCode("var res = $rawReadExpr\n", *args)
                beginControlFlow("if (!sl.validate(stamp))")
                addStatement("stamp = sl.readLock()")
                beginControlFlow("try")
                addCode("res = $rawReadExpr\n", *args)
                nextControlFlow("finally")
                addStatement("sl.unlockRead(stamp)")
                endControlFlow()
                endControlFlow()
                addStatement("return res")
            }
        }
        return getterBuilder.build()
    }

    /**
     * Setter の生成。
     */
    private fun buildSetter(field: XrossField, vhName: String, isLocked: Boolean): FunSpec {
        val setterBuilder = FunSpec.setterBuilder().addParameter("v", field.ty.kotlinType)

        val rawWriteExpr = when (field.ty) {
            is XrossType.Bool -> CodeBlock.of("$vhName.set(segment, 0L, if (v) 1.toByte() else 0.toByte())")
            is XrossType.RustStruct, is XrossType.RustEnum -> CodeBlock.of("$vhName.set(segment, 0L, v.segment)")
            else -> CodeBlock.of("$vhName.set(segment, 0L, v)")
        }

        if (!isLocked) {
            setterBuilder.addCode(rawWriteExpr).addCode("\n")
        } else {
            setterBuilder.apply {
                addStatement("val stamp = sl.writeLock()")
                beginControlFlow("try")
                addCode(rawWriteExpr).addCode("\n")
                nextControlFlow("finally")
                addStatement("sl.unlockWrite(stamp)")
                endControlFlow()
            }
        }
        return setterBuilder.build()
    }

    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        field: XrossField,
        baseName: String,
        escapedName: String,
        vhName: String
    ) {
        val type = field.ty.kotlinType
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"

        val innerClass = TypeSpec.classBuilder(innerClassName)
            .addModifiers(KModifier.INNER)
            .addFunction(
                FunSpec.builder("get").returns(type)
                    .addStatement("return $vhName.getVolatile(segment, 0L) as %T", type).build()
            )
            .addFunction(
                FunSpec.builder("set").addParameter("value", type)
                    .addStatement("$vhName.setVolatile(segment, 0L, value)").build()
            )
            .addFunction(
                FunSpec.builder("set")
                    .addParameter("block", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(type)), returnType = type))
                    .beginControlFlow("while (true)")
                    .addStatement("val expect = get()")
                    .addStatement("val update = block(expect)")
                    .beginControlFlow("if (compareAndSet(expect, update))")
                    .addStatement("break")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
            )
            .addFunction(
                FunSpec.builder("compareAndSet").addParameter("expected", type).addParameter("newValue", type)
                    .returns(BOOLEAN)
                    .addStatement("return $vhName.compareAndSet(segment, 0L, expected, newValue)")
                    .build()
            )
            .build()

        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName))
                .initializer("%L()", innerClassName).build()
        )
    }
}