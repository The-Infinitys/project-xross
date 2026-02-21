package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.FFMConstants.ADDRESS
import org.xross.generator.util.FFMConstants.FUNCTION_DESCRIPTOR
import org.xross.generator.util.FFMConstants.JAVA_BYTE
import org.xross.generator.util.FFMConstants.JAVA_INT
import org.xross.generator.util.FFMConstants.JAVA_LONG
import org.xross.generator.util.GeneratorUtils
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*

object HandleResolver {
    fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        // Basic handles
        init.addStatement(
            "this.xrossFreeBufferHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%L))",
            "xross_free_buffer",
            FUNCTION_DESCRIPTOR,
            FFMConstants.XROSS_STRING_LAYOUT_CODE,
        )

        if (meta !is XrossDefinition.Function) {
            listOf("drop", "layout").forEach { suffix ->
                val symbol = "${meta.symbolPrefix}_$suffix"
                val methodMeta = meta.methods.find { it.name == suffix }
                val handleMode = methodMeta?.handleMode ?: HandleMode.Normal
                val isPanicable = handleMode is HandleMode.Panicable
                val desc = when (suffix) {
                    "drop" -> if (isPanicable) {
                        CodeBlock.of("%T.ofVoid(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
                    } else {
                        CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    }
                    "layout" -> CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    else -> CodeBlock.of("%T.of(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
                }
                val options = when (handleMode) {
                    is HandleMode.Critical -> CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess)
                    else -> CodeBlock.of("")
                }
                init.addStatement("this.${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L%L)", symbol, desc, options)
            }
        }

        when (meta) {
            is XrossDefinition.Struct -> resolveStructHandles(init, meta)
            is XrossDefinition.Enum -> resolveEnumHandles(init, meta)
            is XrossDefinition.Opaque -> resolveOpaqueHandles(init, meta)
            is XrossDefinition.Function -> {}
        }

        resolveMethodHandles(init, meta)
    }

    private fun getArgLayouts(handleMode: HandleMode, fields: List<XrossField>): List<CodeBlock> {
        val layouts = mutableListOf<CodeBlock>()
        fields.forEach { field ->
            val layoutCode = if (handleMode is HandleMode.Critical) field.ty.layoutCodeCritical else field.ty.layoutCode
            when (field.ty) {
                is XrossType.RustString -> { // String args are special
                    layouts.add(CodeBlock.of("%M", ADDRESS)) // ptr
                    layouts.add(CodeBlock.of("%M", JAVA_LONG)) // len
                    layouts.add(CodeBlock.of("%M", JAVA_BYTE)) // encoding
                }

                is XrossType.Slice, is XrossType.Vec -> {
                    // Vec and Slice arguments need ptr and len
                    layouts.add(layoutCode) // ptr (critical or normal ADDRESS)
                    layouts.add(CodeBlock.of("%M", JAVA_LONG)) // len
                }

                else -> {
                    layouts.add(layoutCode)
                }
            }
        }
        return layouts
    }

    private fun resolveStructHandles(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        meta.methods.filter { it.isConstructor }.forEach { method ->
            val argLayouts = getArgLayouts(method.handleMode, method.args)
            val isPanicable = method.handleMode is HandleMode.Panicable

            val desc = if (isPanicable) {
                val allArgs = mutableListOf(CodeBlock.of("%M", ADDRESS))
                allArgs.addAll(argLayouts)
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, allArgs.joinToCode(", "))
            } else {
                val retLayout = CodeBlock.of("%M", ADDRESS)
                if (argLayouts.isEmpty()) {
                    CodeBlock.of("%T.of(%L)", FUNCTION_DESCRIPTOR, retLayout)
                } else {
                    CodeBlock.of("%T.of(%L, %L)", FUNCTION_DESCRIPTOR, retLayout, argLayouts.joinToCode(", "))
                }
            }

            val handleName = GeneratorUtils.getHandleName(method)
            init.addStatement("this.%L = linker.downcallHandle(lookup.find(%S).get(), %L)", handleName, method.symbol, desc)
        }
        resolvePropertyHandles(init, meta.symbolPrefix, meta.fields)
    }

    private fun resolveEnumHandles(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement(
            "this.getTagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
            "${meta.symbolPrefix}_get_tag",
            FUNCTION_DESCRIPTOR,
            JAVA_INT,
            ADDRESS,
        )
        init.addStatement(
            "this.getVariantNameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %M))",
            "${meta.symbolPrefix}_get_variant_name",
            FUNCTION_DESCRIPTOR,
            ADDRESS,
            ADDRESS,
        )

        meta.variants.forEach { v ->
            val argLayouts = getArgLayouts(HandleMode.Normal, v.fields)
            val desc = if (argLayouts.isEmpty()) {
                CodeBlock.of("%T.of(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
            } else {
                CodeBlock.of("%T.of(%M, %L)", FUNCTION_DESCRIPTOR, ADDRESS, argLayouts.joinToCode(", "))
            }
            init.addStatement("this.new${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", "${meta.symbolPrefix}_new_${v.name}", desc)
        }
    }

    private fun resolveOpaqueHandles(init: CodeBlock.Builder, meta: XrossDefinition.Opaque) {
        resolvePropertyHandles(init, meta.symbolPrefix, meta.fields, isOpaque = true)
    }

    private fun resolvePropertyHandles(init: CodeBlock.Builder, prefix: String, fields: List<XrossField>, isOpaque: Boolean = false) {
        fields.forEach { field ->
            val baseCamel = field.name.toCamelCase()
            // Assume HandleMode.Normal for property accessors for now
            addGetterSetter(init, prefix, field.name, baseCamel, field.ty, HandleMode.Normal, isOpaque = isOpaque)
        }
    }

    private fun addGetterSetter(
        init: CodeBlock.Builder,
        prefix: String,
        rawName: String,
        camelName: String,
        fieldType: XrossType,
        handleMode: HandleMode,
        isOpaque: Boolean = false,
    ) {
        val isCritical = handleMode is HandleMode.Critical
        val getLayout = if (isCritical) fieldType.layoutCodeCritical else fieldType.layoutCode
        val setLayout = if (isCritical) fieldType.layoutCodeCritical else fieldType.layoutCode

        when (fieldType) {
            is XrossType.RustString -> {
                val getSymbol = "${prefix}_property_${rawName}_str_get"
                val setSymbol = "${prefix}_property_${rawName}_str_set"
                val getRetLayout = FFMConstants.XROSS_STRING_LAYOUT_CODE

                init.addStatement(
                    "this.${camelName}StrGetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M)%L)",
                    getSymbol,
                    FUNCTION_DESCRIPTOR,
                    getRetLayout,
                    ADDRESS,
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
                init.addStatement(
                    "this.${camelName}StrSetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L)%L)",
                    setSymbol,
                    FUNCTION_DESCRIPTOR,
                    ADDRESS,
                    CodeBlock.of("%M, %M, %M", ADDRESS, JAVA_LONG, JAVA_BYTE),
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
            }
            is XrossType.Optional -> {
                val getSymbol = "${prefix}_property_${rawName}_opt_get"
                val setSymbol = "${prefix}_property_${rawName}_opt_set"

                init.addStatement(
                    "this.${camelName}OptGetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M)%L)",
                    getSymbol,
                    FUNCTION_DESCRIPTOR,
                    getLayout,
                    ADDRESS,
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
                init.addStatement(
                    "this.${camelName}OptSetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L)%L)",
                    setSymbol,
                    FUNCTION_DESCRIPTOR,
                    ADDRESS,
                    setLayout,
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
            }
            is XrossType.Result -> {
                val getSymbol = "${prefix}_property_${rawName}_res_get"
                val setSymbol = "${prefix}_property_${rawName}_res_set"
                val getRetLayout = FFMConstants.XROSS_RESULT_LAYOUT_CODE

                init.addStatement(
                    "this.${camelName}ResGetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M)%L)",
                    getSymbol,
                    FUNCTION_DESCRIPTOR,
                    getRetLayout,
                    ADDRESS,
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
                init.addStatement(
                    "this.${camelName}ResSetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L)%L)",
                    setSymbol,
                    FUNCTION_DESCRIPTOR,
                    ADDRESS,
                    FFMConstants.XROSS_RESULT_LAYOUT_CODE,
                    if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                )
            }
            else -> {
                if (isOpaque) {
                    val getSymbol = "${prefix}_property_${rawName}_get"
                    val setSymbol = "${prefix}_property_${rawName}_set"
                    init.addStatement(
                        "this.${camelName}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M)%L)",
                        getSymbol,
                        FUNCTION_DESCRIPTOR,
                        getLayout,
                        ADDRESS,
                        if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                    )
                    init.addStatement(
                        "this.${camelName}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L)%L)",
                        setSymbol,
                        FUNCTION_DESCRIPTOR,
                        ADDRESS,
                        setLayout,
                        if (isCritical) CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess) else CodeBlock.of("")
                    )
                }
            }
        }
    }

    private fun resolveMethodHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        meta.methods.filter { !it.isConstructor && it.name != "drop" && it.name != "layout" }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            args.addAll(getArgLayouts(method.handleMode, method.args))

            val isComplexRet = method.ret is XrossType.RustString || method.isAsync || method.ret is XrossType.Vec || method.ret is XrossType.Slice

            val isPanicable = method.handleMode is HandleMode.Panicable
            val desc = if (method.ret is XrossType.Void && !method.isAsync && !isPanicable) {
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, args.joinToCode(", "))
            } else if (isPanicable || isComplexRet) {
                val allArgs = mutableListOf(CodeBlock.of("%M", ADDRESS))
                allArgs.addAll(args)
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, allArgs.joinToCode(", "))
            } else {
                val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))
                val retLayout = method.ret.layoutCode
                CodeBlock.of("%T.of(%L%L)", FUNCTION_DESCRIPTOR, retLayout, argsPart)
            }

            val options = when (val mode = method.handleMode) {
                is HandleMode.Critical -> {
                    CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), mode.allowHeapAccess)
                }
                else -> CodeBlock.of("")
            }

            init.addStatement(
                "this.${method.name.toCamelCase()}Handle = linker.downcallHandle(lookup.find(%S).get(), %L%L)",
                method.symbol,
                desc,
                options,
            )
        }
    }
}
