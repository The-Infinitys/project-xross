package org.xross.gradle

/**
 * Extension for configuring the Xross plugin.
 */
abstract class XrossExtension {
    /**
     * Path to the Rust project directory.
     */
    var rustProjectDir: String = ""

    /**
     * Default package name for generated Kotlin files.
     */
    var packageName: String = ""

    /**
     * Default export dir for generated Kotlin files.
     */
    var exportDir: String = "generated/source/xross/main/kotlin"
    private var customMetadataDir: String? = null

    /**
     * Directory where Rust Xross metadata JSON files are located.
     * Defaults to "${rustProjectDir}/target/xross".
     */
    var metadataDir: String
        get() = customMetadataDir ?: if (rustProjectDir.isEmpty()) "target/xross" else "$rustProjectDir/target/xross"
        set(value) {
            customMetadataDir = value
        }

    /**
     * Use Kotlin's unsigned types
     */
    var useUnsignedTypes: Boolean = false

    /**
     * Automatically set sources
     */
    var autoSrc: Boolean = true

    /**
     * List of crates to include for generation. If empty, all found crates are included.
     */
    var includeCrates: Set<String> = emptySet()

    /**
     * List of crates to exclude from generation.
     */
    var excludeCrates: Set<String> = emptySet()
}
