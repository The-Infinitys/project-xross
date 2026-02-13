package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.generator.MethodGenerator.addResourceConstruction
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment
import java.lang.ref.WeakReference

object PropertyGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    fun ClassName(fullName: String): ClassName {
        val lastDot = fullName.lastIndexOf('.')
        return if (lastDot == -1) {
            ClassName("", fullName)
        } else {
            val packageName = fullName.substring(0, lastDot)
            val simpleName = fullName.substring(lastDot + 1)
            ClassName(packageName, simpleName)
        }
    }
    fun resolveReturnType(type: XrossType, basePackage: String): TypeName = when (type) {
        is XrossType.Void -> Unit::class.asTypeName()
        is XrossType.RustString -> String::class.asTypeName()
        is XrossType.Object -> ClassName("$basePackage.${type.signature}")
        is XrossType.Optional -> resolveReturnType(type.inner, basePackage).copy(nullable = true)
        is XrossType.Result -> {
            val okType = resolveReturnType(type.ok, basePackage)
            ClassName("kotlin", "Result").parameterizedBy(okType)
        }
        // XrossTypeが持つデフォルトのkotlinTypeプロパティ（Int, Long, Boolean等）
        else -> type.kotlinType
    }
    fun compareExprs(
        targetTypeName: TypeName,
        selfType: ClassName,
        dropHandleName: String = "dropHandle",
    ): Triple<CodeBlock, CodeBlock, CodeBlock> {
        // targetTypeNameがNullableの場合を考慮して比較
        val isSelf = targetTypeName.copy(nullable = false) == selfType
        val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", targetTypeName)
        val dropExpr = if (isSelf) CodeBlock.of(dropHandleName) else CodeBlock.of("%T.dropHandle", targetTypeName)
        val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", targetTypeName)
        return Triple(sizeExpr, dropExpr, fromPointerExpr)
    }

    /**
     * MemorySegment (ptr) から XrossType に基づいた Kotlin の値を生成するコードを注入する。
     * Result の Ok/Err 分岐内や、Optional の非NULL分岐内で使用。
     */
    fun CodeBlock.Builder.addResultVariantResolution(
        type: XrossType,
        ptrName: String,
        targetTypeName: TypeName,
        selfType: ClassName,
        basePackage: String,
        dropHandleName: String = "dropHandle",
    ) {
        val isSelf = targetTypeName.copy(nullable = false) == selfType
        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        // Object/String/Primitive ごとの解決ロジック
        when (type) {
            is XrossType.Object -> {
                val (sizeExpr, dropExpr, fromPointerExpr) = compareExprs(targetTypeName, selfType, dropHandleName)
                this.addResourceConstruction(type, ptrName, sizeExpr, fromPointerExpr, dropExpr, flagType)
            }

            is XrossType.RustString -> {
                // 文字列の場合は解決して値を置く
                GeneratorUtils.addRustStringResolution(this, ptrName)
                this.addStatement("str")
            }

            else -> {
                // プリミティブ（Int, Long等）
                // ポインタから値を取得し、一時的な MemorySegment をドロップする
                this.addStatement("val v = %L.get(%M, 0)", ptrName, type.layoutMember)

                // dropHandle が利用可能な文脈（Result/Optionalの戻り値等）であれば呼び出す
                val dropExpr = if (isSelf) CodeBlock.of(dropHandleName) else CodeBlock.of("%T.dropHandle", targetTypeName)
                this.addStatement("%L.invokeExact(%L)", dropExpr, ptrName)
                this.addStatement("v")
            }
        }
    }
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, basePackage: String) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val kType = if (field.ty is XrossType.Object) {
                GeneratorUtils.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            var backingFieldName: String? = null
            if (field.ty is XrossType.Object) {
                backingFieldName = "_$baseName"
                val weakRefType = ClassName("java.lang.ref", "WeakReference").parameterizedBy(kType)
                val backingProp = PropertySpec.builder(backingFieldName, weakRefType.copy(nullable = true))
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
                    .getter(buildGetter(field, vhName, kType, backingFieldName, selfType, basePackage))

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, kType, backingFieldName, selfType))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"
        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        when (val ty = field.ty) {
            is XrossType.Object -> {
                // キャッシュチェック
                body.addStatement("val cached = this.$backingFieldName?.get()")
                body.beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                body.addStatement("res = cached")
                body.nextControlFlow("else")

                val isOwned = ty.ownership == XrossType.Ownership.Owned
                val sizeExpr = if (kType == selfType) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                val fromPointerExpr = if (kType == selfType) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                val ffiHelpers = ClassName("$basePackage.xross.runtime", "FfiHelpers")

                body.addStatement("val resSeg = %T.resolveFieldSegment(this.segment, ${if (isOwned) "null" else vhName}, $offsetName, $sizeExpr, $isOwned)", ffiHelpers)
                body.addStatement("res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                body.addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                body.endControlFlow()
            }

            is XrossType.Optional -> {
                body.addStatement("val resRaw = ${baseName}OptGetHandle.invokeExact(this.segment) as %T", MemorySegment::class)
                body.beginControlFlow("if (resRaw == %T.NULL)", MemorySegment::class).addStatement("res = null").nextControlFlow("else")
                // 拡張関数で解決
                body.addResultVariantResolution(ty.inner, "resRaw", resolveReturnType(ty.inner, basePackage), selfType, basePackage)
                body.addStatement("res = it") // addResultVariantResolutionの戻り値を受け取る
                body.endControlFlow()
            }

            is XrossType.Result -> {
                body.addStatement("val resRaw = ${baseName}ResGetHandle.invokeExact(this.segment) as %T", MemorySegment::class)
                body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", MemberName("java.lang.foreign.ValueLayout", "JAVA_BYTE"))
                body.addStatement("val ptr = resRaw.get(%M, 8L)", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"))

                body.beginControlFlow("if (isOk)")
                body.add("val okVal = ")
                body.addResultVariantResolution(ty.ok, "ptr", resolveReturnType(ty.ok, basePackage), selfType, basePackage)
                body.addStatement("res = Result.success(okVal) as %T", kType)

                body.nextControlFlow("else")
                body.add("val errVal = ")
                body.addResultVariantResolution(ty.err, "ptr", resolveReturnType(ty.err, basePackage), selfType, basePackage)
                body.addStatement("res = Result.failure<%T>(%T(errVal)) as %T", resolveReturnType(ty.ok, basePackage), ClassName("$basePackage.xross.runtime", "XrossException"), kType)
                body.endControlFlow()
            }

            is XrossType.RustString -> {
                val callExpr = "${baseName}StrGetHandle.invokeExact(this.segment)"
                GeneratorUtils.addRustStringResolution(body, callExpr, "res", isAssignment = true)
            }

            is XrossType.Bool -> body.addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
            else -> body.addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
        }

        return GeneratorUtils.buildOptimisticReadGetter(kType, body.build())
    }
    private fun buildSetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Access")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (val ty = field.ty) {
            is XrossType.Object -> {
                body.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Arg")
                if (ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (kType == selfType) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                    body.addStatement("this.segment.asSlice($offsetName, %L).copyFrom(v.segment)", sizeExpr)
                } else {
                    body.addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                }
                if (backingFieldName != null) body.addStatement("this.$backingFieldName = null")
            }

            // String, Optional, Result はすべて Temporary Arena を必要とするグループ
            is XrossType.RustString, is XrossType.Optional, is XrossType.Result -> {
                body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
                when (ty) {
                    is XrossType.RustString -> {
                        body.addStatement("${baseName}StrSetHandle.invokeExact(this.segment, arena.allocateFrom(v)) as Unit")
                    }
                    is XrossType.Optional -> {
                        body.addStatement("val allocated = if (v == null) %T.NULL else %L", MemorySegment::class, generateAllocMsg(ty.inner, "v"))
                        body.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                    }
                    is XrossType.Result -> {
                        // Resultの構造体構築を共通化
                        body.addStatement("val xrossRes = arena.allocate(GeneratorUtils.RESULT_LAYOUT)")
                        body.beginControlFlow("if (v.isSuccess)")
                        body.addStatement("xrossRes.set(%T.JAVA_BYTE, 0L, 1.toByte())", VAL_LAYOUT)
                        body.addStatement("val okAllocated = %L", generateAllocMsg(ty.ok, "v.getOrThrow()"))
                        body.addStatement("xrossRes.set(%T.ADDRESS, 8L, okAllocated)", VAL_LAYOUT)
                        body.nextControlFlow("else")
                        body.addStatement("xrossRes.set(%T.JAVA_BYTE, 0L, 0.toByte())", VAL_LAYOUT)
                        body.addStatement("xrossRes.set(%T.ADDRESS, 8L, %T.NULL)", VAL_LAYOUT, MemorySegment::class)
                        body.endControlFlow()
                        body.addStatement("${baseName}ResSetHandle.invokeExact(this.segment, xrossRes) as Unit")
                    }
                }
                body.endControlFlow()
            }

            is XrossType.Bool -> body.addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
            else -> body.addStatement("$vhName.set(this.segment, $offsetName, v)")
        }

        // 共通のLockラッパーでラップして返す
        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
            "val stamp = this.sl.writeLock()\ntry { %L } finally { this.sl.unlockWrite(stamp) }",
            body.build(),
        ).build()
    }

    // ヘルパー: 型に応じた allocate 式を返す
    private fun generateAllocMsg(ty: XrossType, valueName: String): CodeBlock = when (ty) {
        is XrossType.Object -> CodeBlock.of("$valueName.segment")
        is XrossType.RustString -> CodeBlock.of("arena.allocateFrom($valueName)")
        else -> CodeBlock.of("arena.allocate(%M, $valueName)", ty.layoutMember)
    }
    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        baseName: String,
        escapedName: String,
        vhName: String,
        kType: TypeName,
    ) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val offsetName = "OFFSET_$baseName"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return $vhName.getVolatile(this@${className(classBuilder)}.segment, $offsetName) as %T", kType)
                            .build(),
                    ).build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                    .beginControlFlow("while (true)")
                    .beginControlFlow("try")
                    .addStatement("val current = value")
                    .addStatement("val next = block(current)")
                    .beginControlFlow("if ($vhName.compareAndSet(this@${className(classBuilder)}.segment, $offsetName, current, next))")
                    .addStatement("return next")
                    .endControlFlow()
                    .nextControlFlow("catch (e: %T)", Throwable::class)
                    .addStatement("throw e")
                    .endControlFlow()
                    .endControlFlow().build(),
            )
            .build()
        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName)).initializer("%L()", innerClassName).build(),
        )
    }

    private fun className(builder: TypeSpec.Builder): String = builder.build().name!!
}
