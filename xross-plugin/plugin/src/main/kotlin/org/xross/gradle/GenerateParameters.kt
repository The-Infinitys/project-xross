package org.xross.gradle

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters
import java.io.File

interface GenerateParameters : WorkParameters {
    val jsonContent: Property<String>
    val outputDir: Property<File>
    val packageName: Property<String>
    val typeMapping: MapProperty<String, String>
    val useUnsignedTypes: Property<Boolean>
}
