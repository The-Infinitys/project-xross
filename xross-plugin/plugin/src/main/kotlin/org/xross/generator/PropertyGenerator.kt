package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossThreadSafety

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.fields.forEach { field ->
            val baseCamelName = field.name.toCamelCase()
            val escapedName = baseCamelName.escapeKotlinKeyword()
            val propType = field.ty.kotlinType
            val offsetName = "OFFSET_${field.name}"
            val safety = field.safety

            when (safety) {
                // 1. Atomic: VarHandle を使用した完全マルチスレッド (CAS, GetAndAdd 等を提供)
                XrossThreadSafety.Atomic -> {
                    generateAtomicProperty(classBuilder, baseCamelName, escapedName, propType, offsetName)
                }

                // 2. Lock: StampedLock を使用した楽観的読み取り
                XrossThreadSafety.Lock -> {
                    generateLockProperty(classBuilder, escapedName, propType, offsetName)
                }

                // 3. Unsafe: 防御なし、最速アクセス
                XrossThreadSafety.Unsafe -> {
                    generateUnsafeProperty(classBuilder, escapedName, propType, offsetName)
                }

                // 4. Immutable: 不変参照、Getter のみ提供
                XrossThreadSafety.Immutable -> {
                    generateImmutableProperty(classBuilder, escapedName, propType, offsetName)
                }
            }
        }
    }

    private fun generateUnsafeProperty(classBuilder: TypeSpec.Builder, name: String, type: TypeName, offset: String) {
        val getter = FunSpec.getterBuilder()
            .addStatement("return segment.get(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset)") // 型に応じたレイアウトが必要ですが簡易化
            .build()
        val setter = FunSpec.setterBuilder()
            .addParameter("value", type)
            .addStatement("segment.set(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset, value)")
            .build()

        classBuilder.addProperty(PropertySpec.builder(name, type).mutable(true).getter(getter).setter(setter).build())
    }

    private fun generateLockProperty(classBuilder: TypeSpec.Builder, name: String, type: TypeName, offset: String) {
        // Getter: 楽観読み取り
        val getter = FunSpec.getterBuilder()
            .addStatement("var stamp = sl.tryOptimisticRead()")
            // 読み取り対象。VarHandle があれば getVolatile、なければ get (ValueLayout)
            .addStatement("var res = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset)")
            .beginControlFlow("if (!sl.validate(stamp))")
            .addStatement("stamp = sl.readLock()")
            .beginControlFlow("try")
            .addStatement("res = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset)")
            .nextControlFlow("finally")
            .addStatement("sl.unlockRead(stamp)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return res")
            .build()

        // Setter: WriteLock
        val setter = FunSpec.setterBuilder()
            .addParameter("value", type)
            .addStatement("val stamp = sl.writeLock()")
            .beginControlFlow("try")
            .addStatement("segment.set(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset, value)")
            .nextControlFlow("finally")
            .addStatement("sl.unlockWrite(stamp)")
            .endControlFlow()
            .build()

        classBuilder.addProperty(PropertySpec.builder(name, type).mutable(true).getter(getter).setter(setter).build())
    }

    private fun generateAtomicProperty(classBuilder: TypeSpec.Builder, baseName: String, escapedName: String, type: TypeName, offset: String) {
        val vhName = "VH_$baseName"

        val getter = FunSpec.getterBuilder()
            .addStatement("return $vhName.getVolatile(segment, $offset.offset) as %T", type)
            .build()

        val setter = FunSpec.setterBuilder()
            .addParameter("value", type)
            .addStatement("$vhName.setVolatile(segment, $offset.offset, value)")
            .build()

        classBuilder.addProperty(PropertySpec.builder(escapedName, type).mutable(true).getter(getter).setter(setter).build())

        // Atomic Helpers
        val capitalized = baseName.replaceFirstChar { it.uppercase() }
        classBuilder.addFunction(
            FunSpec.builder("compareAndSet$capitalized")
                .addParameter("expected", type)
                .addParameter("newValue", type)
                .returns(Boolean::class)
                .addStatement("return $vhName.compareAndSet(segment, $offset.offset, expected, newValue)")
                .build()
        )

        if (type == INT || type == LONG) {
            classBuilder.addFunction(
                FunSpec.builder("getAndAdd$capitalized")
                    .addParameter("delta", type)
                    .returns(type)
                    .addStatement("return $vhName.getAndAdd(segment, $offset.offset, delta) as %T", type)
                    .build()
            )
        }
    }

    private fun generateImmutableProperty(classBuilder: TypeSpec.Builder, name: String, type: TypeName, offset: String) {
        // Immutable は val (Setterなし)。不変参照なので楽観読み取りすら不要（一度決まれば変わらない前提、または writeLock 側で整合性を取る）
        val getter = FunSpec.getterBuilder()
            .addStatement("return segment.get(java.lang.foreign.ValueLayout.JAVA_INT, $offset.offset)")
            .build()

        classBuilder.addProperty(PropertySpec.builder(name, type).mutable(false).getter(getter).build())
    }
}
