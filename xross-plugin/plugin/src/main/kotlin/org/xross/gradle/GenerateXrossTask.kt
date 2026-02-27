package org.xross.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

// --- 並列実行タスク ---
abstract class GenerateXrossTask
@Inject
constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val metadataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    abstract val useUnsignedTypes: Property<Boolean>

    @TaskAction
    fun execute() {
        val outDir = outputDir.get().asFile
        val mDir = metadataDir.get().asFile
        println("Generating Xross bindings to: ${outDir.absolutePath}")
        println("Metadata directory: ${mDir.absolutePath}")
        outDir.deleteRecursively()
        outDir.mkdirs()
        val jsonFiles = mDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        println("Found ${jsonFiles.size} JSON files")
        val queue = workerExecutor.noIsolation()
        jsonFiles.forEach { file ->
            println("Submitting GenerateAction for: ${file.name}")
            queue.submit(GenerateAction::class.java) { params ->
                params.jsonFile.set(file)
                params.outputDir.set(outDir)
                params.packageName.set(packageName)
                params.metadataDir.set(metadataDir.get().asFile)
                params.useUnsignedTypes.set(useUnsignedTypes.get())
            }
        }
        queue.await()
    }
}
