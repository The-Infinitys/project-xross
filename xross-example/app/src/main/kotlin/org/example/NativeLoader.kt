package org.example

import java.io.File
import java.nio.file.Files

object NativeLoader {
    private var loaded = false

    fun load() {
        if (loaded) return
        val libName = "libxross_example.so"
        val tempDir: File = Files.createTempDirectory("xross_lib_").toFile()
        val libFile = File(tempDir, libName)

        val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
            ?: throw RuntimeException("Resource not found: $libName. Ensure it is in src/main/resources/")

        resourceStream.use { input ->
            libFile.outputStream().use { output -> input.copyTo(output) }
        }

        System.load(libFile.absolutePath)
        println("Native library loaded from: ${libFile.absolutePath}")
        loaded = true

        // 終了時に削除予約
        tempDir.deleteOnExit()
        libFile.deleteOnExit()
    }
}
