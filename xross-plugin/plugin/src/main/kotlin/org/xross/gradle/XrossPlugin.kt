package org.xross.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import javax.inject.Inject

/**
 * Gradle plugin for generating Kotlin bindings from Rust Xross metadata.
 */
@Suppress("unused")
class XrossPlugin
@Inject
constructor() : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("xross", XrossExtension::class.java)
        val outDir = project.file(project.layout.buildDirectory.dir(extension.exportDir))
        val generateXrossBindings =
            project.tasks.register("generateXrossBindings", GenerateXrossTask::class.java) { task ->
                val metadataDir = project.file(extension.metadataDir)
                task.metadataDir.set(metadataDir)
                task.outputDir.set(outDir)
                task.packageName.set(extension.packageName)
                task.useUnsignedTypes.set(extension.useUnsignedTypes)
                task.includeCrates.set(extension.includeCrates)
                task.excludeCrates.set(extension.excludeCrates)
            }

        project.afterEvaluate {
            project.extensions.findByType(SourceSetContainer::class.java)?.named("main") { ss ->
                if (extension.autoSrc) {
                    ss.java.srcDir(generateXrossBindings)
                }
            }
        }
    }
}
