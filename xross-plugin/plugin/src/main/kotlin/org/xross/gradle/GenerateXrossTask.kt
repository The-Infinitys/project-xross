package org.xross.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

// --- 並列実行タスク ---
abstract class GenerateXrossTask
@Inject
constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    @get:InputDirectory
    abstract val metadataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    abstract val useUnsignedTypes: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val includeCrates: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val excludeCrates: SetProperty<String>

    @TaskAction
    fun execute() {
        val mAbs = metadataDir.get().asFile
        val outAbs = outputDir.get().asFile
        if (!mAbs.exists()) {
            throw org.gradle.api.GradleException("Metadata directory does not exist: ${mAbs.absolutePath}")
        }
        // --- 安全策：ディレクトリの重複チェック ---
        val mPath = mAbs.canonicalPath
        val outPath = outAbs.canonicalPath

        if (!mAbs.exists()) {
            println("Metadata directory does not exist: ${mAbs.absolutePath}")
            return
        }

        val includes = includeCrates.getOrElse(emptySet())
        val excludes = excludeCrates.getOrElse(emptySet())

        println("Generating Xross bindings to: ${outAbs.absolutePath}")
        println("Metadata directory: ${mAbs.absolutePath}")
        if (outPath == mPath || mPath.startsWith(outPath + java.io.File.separator)) {
            throw org.gradle.api.GradleException(
                "Xross configuration error: Output directory ($outPath) overlaps with metadata directory ($mPath).",
            )
        }

        // 共通ランタイムの生成
        if (!outAbs.exists()) outAbs.mkdirs()
        org.xross.generator.RuntimeGenerator.generate(outAbs, packageName.get())

        // JSONファイルの列挙
        val allJsonFiles = mAbs.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        val jsonFiles = allJsonFiles.filter { file ->
            val relativePath = file.relativeTo(mAbs)
            val segments = relativePath.path.split(java.io.File.separatorChar)
            val crateName = if (segments.size > 1) segments[0] else mAbs.name
            !excludes.contains(crateName) && (includes.isEmpty() || includes.contains(crateName))
        }

        println("Found ${jsonFiles.size} JSON files")

        // 事前スキャンの実施（ワーカーが読み取る必要がないよう、メモリ上に保持する）
        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val mapping = mutableMapOf<String, String>()
        val fileContents = mutableMapOf<java.io.File, String>()

        allJsonFiles.forEach { file ->
            try {
                val text = file.readText()
                fileContents[file] = text
                val def = jsonParser.decodeFromString<org.xross.structures.XrossDefinition>(text)
                mapping[def.name] = def.signature
            } catch (_: Exception) { /* Ignore */ }
        }

        val queue = workerExecutor.noIsolation()
        jsonFiles.forEach { file ->
            val content = fileContents[file] ?: return@forEach
            queue.submit(GenerateAction::class.java) { params ->
                params.jsonContent.set(content) // 文字列として渡す
                params.outputDir.set(outAbs)
                params.packageName.set(packageName)
                params.typeMapping.putAll(mapping)
                params.useUnsignedTypes.set(useUnsignedTypes.get())
            }
        }
        queue.await()
    }
}
