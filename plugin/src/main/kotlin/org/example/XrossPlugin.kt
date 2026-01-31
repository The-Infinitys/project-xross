package org.example

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import org.gradle.api.Plugin
import org.gradle.api.Project
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

// xross-metadata クレートからコピーしたデータクラスをKotlinで定義
// もしくは xross-metadata クレートから生成されたKotlinコードを使用する
// ここでは、JSONパースのために一時的にデータクラスを定義する。
// 最終的には、xross-metadata から生成されたKotlinコードを使用する形になるはず。
// 便宜上、一旦コピーした定義を使用する。

@kotlinx.serialization.Serializable
data class FieldMetadata(
    val name: String,
    val rust_type: String,
    val ffi_getter_name: String,
    val ffi_setter_name: String,
    val ffi_type: String,
)

@kotlinx.serialization.Serializable
data class MethodMetadata(
    val name: String,
    val rust_type: String, // 実際には、ffi_name を持っているが、ここでは rust_type に仮置
    val ffi_name: String,
    val args: List<String>,
    val return_type: String,
    val has_self: Boolean,
    val is_static: Boolean,
)

@kotlinx.serialization.Serializable
data class StructMetadata(
    val name: String,
    val ffi_prefix: String,
    val new_fn_name: String,
    val drop_fn_name: String,
    val clone_fn_name: String,
    val fields: List<FieldMetadata>,
    val methods: List<MethodMetadata>,
)

@kotlinx.serialization.Serializable
data class XrossCombinedMetadata(
    val structs: List<StructMetadata>,
    val methods: List<MethodMetadata>,
)

class XrossPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register a task
        project.tasks.register("greeting") { task ->
            task.doLast {
                println("Hello from plugin 'org.example.greeting'")
            }
        }

        val rustBuildDir = project.layout.buildDirectory.dir("rust").get().asFile
        val metadataFile = rustBuildDir.resolve("xross_metadata.json")

        // generateXrossKotlin タスクを登録
        project.tasks.register("generateXrossKotlin") { task ->
            task.group = "build"
            task.description = "Generates Kotlin wrapper code from Rust metadata."
            task.dependsOn("buildRust") // buildRust タスクに依存

            task.inputs.file(metadataFile)
            task.outputs.dir(project.layout.buildDirectory.dir("generated/xross/src/main/kotlin"))

            task.doLast {
                if (!metadataFile.exists()) {
                    project.logger.error("xross_metadata.json not found at ${metadataFile.absolutePath}. Run 'buildRust' task first.")
                    return@doLast
                }

                val jsonString = metadataFile.readText()
                val metadata = Json.decodeFromString<XrossCombinedMetadata>(jsonString)

                project.logger.lifecycle("Successfully loaded metadata: $metadata")

                generateKotlinCode(project, metadata)
            }
        }

        project.tasks.named("processResources") {
            dependsOn("generateXrossKotlin") // generateXrossKotlin タスクに依存
            doFirst {
                // generateKotlinCode の呼び出しは generateXrossKotlin タスクに移動したため、ここでは不要
                // 既存のロジックは残す
                if (!metadataFile.exists()) {
                    project.logger.error("xross_metadata.json not found at ${metadataFile.absolutePath}. Run 'buildRust' task first.")
                    return@doFirst
                }

                val jsonString = metadataFile.readText()
                val metadata = Json.decodeFromString<XrossCombinedMetadata>(jsonString)

                project.logger.lifecycle("Successfully loaded metadata: $metadata")
            }
        }
    }

    private fun generateKotlinCode(project: Project, metadata: XrossCombinedMetadata) {
        val generatedSourceDir = project.layout.buildDirectory.dir("generated/xross/src/main/kotlin").get().asFile
        generatedSourceDir.mkdirs()

        // Kotlin Native の System.loadLibrary を呼び出すためのヘルパー関数 (仮)
        // 実際には、プラットフォームごとに適切な方法でライブラリをロードする必要がある
        val loadLibraryFun = FunSpec.builder("loadXrossLibrary")
            .addModifiers(KModifier.PRIVATE)
            .addCode(
                """
                // TODO: Implement platform-specific library loading
                // For now, assume library is loaded or handled externally
                System.loadLibrary("xross_core") // Example for JVM
                """.trimIndent()
            )
            .build()


        metadata.structs.forEach { structMeta ->
            val className = ClassName("org.example.xross", structMeta.name)
            val classBuilder = TypeSpec.classBuilder(className)
                .addModifiers(KModifier.PUBLIC)
                .addSuperinterface(ClassName("java.lang", "AutoCloseable")) // AutoCloseable を実装

            // プライマリコンストラクタ private constructor(private val ptr: Long)
            val primaryConstructor = FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("ptr", Long::class)
                .build()
            classBuilder.primaryConstructor(primaryConstructor)
            classBuilder.addProperty(PropertySpec.builder("ptr", Long::class)
                .addModifiers(KModifier.PRIVATE)
                .initializer("ptr")
                .build())

            // close() メソッドの実装
            classBuilder.addFunction(FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(
                    """
                    // Rust の drop 関数を呼び出す
                    // 実際には #drop_fn_name が必要
                    // ここでは仮実装
                    // ${structMeta.drop_fn_name}(ptr)
                    project.logger.lifecycle("Calling Rust drop for ${structMeta.name} with ptr \$ptr")
                    """.trimIndent()
                )
                .build())

            // Companion Object の追加
            val companionObjectBuilder = TypeSpec.companionObjectBuilder()
                .addFunction(FunSpec.builder("new") // ファクトリメソッド
                    .addModifiers(KModifier.PUBLIC)
                    .returns(className)
                    .addCode(
                        """
                        val ptr = ${structMeta.new_fn_name}()
                        return %T(ptr)
                        """.trimIndent(),
                        className // %T で className を参照
                    )
                    .build()
                )
                // Rustの_new関数に対応する外部関数宣言 (JNI/FFI呼び出し用)
                .addFunction(FunSpec.builder(structMeta.new_fn_name)
                    .addModifiers(KModifier.EXTERNAL)
                    .returns(Long::class)
                    .build()
                )
                // Rustの_clone関数に対応する外部関数宣言 (JNI/FFI呼び出し用)
                .addFunction(FunSpec.builder(structMeta.clone_fn_name)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("ptr", Long::class)
                    .returns(Long::class)
                    .build()
                )
            
            classBuilder.addType(companionObjectBuilder.build()) // Companion Object をクラスに追加

            // clone() メソッドの実装
            classBuilder.addFunction(FunSpec.builder("clone")
                .addModifiers(KModifier.PUBLIC)
                .returns(className)
                .addCode(
                    """
                    val newPtr = %L(this.ptr)
                    return %T(newPtr)
                    """.trimIndent(),
                    structMeta.clone_fn_name, // Rustのclone FFI関数名
                    className // %T で className を参照
                )
                .build())

            // Rustフィールドに対応するKotlinプロパティとGetter/Setterを生成
            structMeta.fields.forEach { fieldMeta ->
                val kotlinType = rustTypeToKotlinType(fieldMeta.ffi_type, "org.example.xross") // FFI Type をもとに Kotlin Type を取得

                // FFI Getter 宣言
                companionObjectBuilder.addFunction(FunSpec.builder(fieldMeta.ffi_getter_name)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("ptr", Long::class)
                    .returns(kotlinType) // Kotlin Type を返す
                    .build())

                // FFI Setter 宣言
                companionObjectBuilder.addFunction(FunSpec.builder(fieldMeta.ffi_setter_name)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("ptr", Long::class)
                    .addParameter("value", kotlinType) // Kotlin Type を受け取る
                    .build())

                val propertyBuilder = PropertySpec.builder(fieldMeta.name, kotlinType)
                    .mutable(true) // var プロパティ
                    .getter(FunSpec.getterBuilder()
                        .addCode(
                            """
                            return %L(ptr)
                            """.trimIndent(),
                            fieldMeta.ffi_getter_name
                        )
                        .build())
                    .setter(FunSpec.setterBuilder()
                        .addParameter("value", kotlinType)
                        .addCode(
                            """
                            %L(ptr, value)
                            """.trimIndent(),
                            fieldMeta.ffi_setter_name
                        )
                        .build())
                classBuilder.addProperty(propertyBuilder.build())
            }

            // Rustのメソッドに対応するKotlinのメンバー関数または`companion object`関数を生成
            // XrossCombinedMetadata の methods (implブロック外のフリー関数)
            metadata.methods.forEach { methodMeta ->
                if (methodMeta.is_static) { // フリー関数は static と見なす
                    // FFI 関数宣言
                    companionObjectBuilder.addFunction(FunSpec.builder(methodMeta.ffi_name)
                        .addModifiers(KModifier.EXTERNAL)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .build())
                    
                    // Kotlin ラッパー関数
                    companionObjectBuilder.addFunction(FunSpec.builder(methodMeta.name)
                        .addModifiers(KModifier.PUBLIC)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .addCode(
                            """
                            %L() // Rust FFI 関数を呼び出す
                            """.trimIndent(),
                            methodMeta.ffi_name
                        )
                        .build())
                }
            }

            // StructMetadata の methods (implブロック内のメソッド)
            structMeta.methods.forEach { methodMeta ->
                if (methodMeta.is_static) { // associated function
                    // FFI 関数宣言
                    companionObjectBuilder.addFunction(FunSpec.builder(methodMeta.ffi_name)
                        .addModifiers(KModifier.EXTERNAL)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .build())

                    // Kotlin ラッパー関数
                    companionObjectBuilder.addFunction(FunSpec.builder(methodMeta.name)
                        .addModifiers(KModifier.PUBLIC)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .addCode(
                            """
                            %L() // Rust FFI 関数を呼び出す
                            """.trimIndent(),
                            methodMeta.ffi_name
                        )
                        .build())
                } else { // instance method
                    // FFI 関数宣言
                    classBuilder.addFunction(FunSpec.builder(methodMeta.ffi_name)
                        .addModifiers(KModifier.EXTERNAL)
                        .addParameter("ptr", Long::class)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .build())

                    // Kotlin ラッパー関数
                    classBuilder.addFunction(FunSpec.builder(methodMeta.name)
                        .addModifiers(KModifier.PUBLIC)
                        // TODO: 引数と戻り値の型変換を実装
                        .returns(Unit::class) // 戻り値は仮
                        .addCode(
                            """
                            %L(this.ptr) // Rust FFI 関数を呼び出す
                            """.trimIndent(),
                            methodMeta.ffi_name
                        )
                        .build())
                }
            }


            val fileBuilder = FileSpec.builder("org.example.xross", structMeta.name)
                .addType(classBuilder.build())
                .addFunction(loadLibraryFun)

            
            val file = fileBuilder.build()
            file.writeTo(generatedSourceDir)
            project.logger.lifecycle("Generated Kotlin file: ${file.name} in $generatedSourceDir")
        }
    }
}

fun rustTypeToKotlinType(rustType: String, packageName: String): ClassName {
    return when (rustType) {
        "u8" -> ClassName("kotlin", "UByte")
        "i8" -> ClassName("kotlin", "Byte")
        "u16" -> ClassName("kotlin", "UShort")
        "i16" -> ClassName("kotlin", "Short")
        "u32" -> ClassName("kotlin", "UInt")
        "i32" -> ClassName("kotlin", "Int")
        "u64" -> ClassName("kotlin", "ULong")
        "i64" -> ClassName("kotlin", "Long")
        "f32" -> ClassName("kotlin", "Float")
        "f64" -> ClassName("kotlin", "Double")
        "bool" -> ClassName("kotlin", "Boolean")
        "*const libc::c_char" -> ClassName("kotlin", "String")
        // JvmClassType の場合、*mut を除外して ClassName を構築
        // 例: *mut MyStruct -> MyStruct
        else -> {
            val actualType = rustType.removePrefix("*mut ").removePrefix("*const ").trim()
            ClassName(packageName, actualType)
        }
    }
}