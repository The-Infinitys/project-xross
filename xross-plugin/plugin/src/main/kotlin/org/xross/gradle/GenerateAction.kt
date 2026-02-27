package org.xross.gradle

import org.gradle.workers.WorkAction
import org.xross.generator.XrossGenerator

abstract class GenerateAction : WorkAction<GenerateParameters> {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun execute() {
        val jsonContent = parameters.jsonContent.get()
        val meta = json.decodeFromString<org.xross.structures.XrossDefinition>(jsonContent)

        val basePackage = parameters.packageName.get()
        val fullPackage = if (meta.packageName.isBlank()) {
            basePackage
        } else {
            "$basePackage.${meta.packageName}"
        }

        val outputBaseDir = parameters.outputDir.get()

        // TypeResolver は事前スキャン済みマッピングを使用する
        val typeMapping = parameters.typeMapping.get()
        val resolver = org.xross.generator.TypeResolver(java.io.File("UNUSED"), typeMapping)

        XrossGenerator.property.useUnsignedTypes = parameters.useUnsignedTypes.get()

        XrossGenerator.generate(
            meta,
            outputBaseDir,
            fullPackage,
            basePackage,
            resolver,
        )
    }
}
