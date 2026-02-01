package org.xross

import com.squareup.kotlinpoet.*
import java.io.File

object XrossGenerator {
    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val fileSpec = FileSpec.builder(targetPackage, meta.structName)
            .addImport("java.lang.foreign", "ValueLayout", "Linker", "SegmentAllocator", "Arena")
            .addImport("java.lang.invoke", "MethodHandle")
            .addImport("java.io", "Closeable")
            .addImport("java.lang", "IllegalStateException")
            .addImport("com.squareup.kotlinpoet", "KModifier")
        fileSpec.build().writeTo(outputDir)
    }

}