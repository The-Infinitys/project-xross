package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.XrossClass
import org.xross.XrossMethodType
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        // クラス内に可変メソッドが存在するか確認
        val hasMutMethods = meta.methods.any { it.methodType == XrossMethodType.MutInstance }

        meta.fields.forEach { field ->
            val camelName = field.name.toCamelCase().escapeKotlinKeyword()
            val propType = field.ty.kotlinType
            val offsetName = "OFFSET_${field.name}"

            val getterBuilder = FunSpec.getterBuilder()
            val setterBuilder = FunSpec.setterBuilder().addParameter("value", propType)

            if (hasMutMethods) {
                // スレッドセーフな実装 (ReadLock / WriteLock)
                getterBuilder.addStatement(
                    "return lock.readLock().withLock { segment.get(%M, $offsetName.offset) }",
                    field.ty.layoutMember
                )
                setterBuilder.addStatement(
                    "lock.writeLock().withLock { segment.set(%M, $offsetName.offset, value) }",
                    field.ty.layoutMember
                )
            } else {
                // ロックなしの直接アクセス
                getterBuilder.addStatement(
                    "return segment.get(%M, $offsetName.offset)",
                    field.ty.layoutMember
                )
                setterBuilder.addStatement(
                    "segment.set(%M, $offsetName.offset, value)",
                    field.ty.layoutMember
                )
            }

            val prop = PropertySpec.builder(camelName, propType)
                .mutable(true)
                .getter(getterBuilder.build())
                .setter(setterBuilder.build())
                .build()

            classBuilder.addProperty(prop)
        }
    }
}
