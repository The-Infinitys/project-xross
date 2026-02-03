package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossType
import java.io.File
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

object XrossGenerator {
    private val opaqueObjects = ConcurrentHashMap.newKeySet<String>()

    fun generate(meta: XrossDefinition, outputDir: File, targetPackage: String) {
        val className = meta.name

        // 不透明型の収集
        collectOpaqueTypes(meta)

        // 1. クラスの基本構造を決定
        val classBuilder = when (meta) {
            is XrossDefinition.Struct -> {
                TypeSpec.classBuilder(className)
                    .addModifiers(KModifier.OPEN) // 継承を許可
                    .addSuperinterface(AutoCloseable::class)
            }

            is XrossDefinition.Enum -> {
                // すべてのバリアントが空なら単純な enum class、そうでなければ sealed class
                if (meta.variants.all { it.fields.isEmpty() }) {
                    TypeSpec.enumBuilder(className)
                        .addSuperinterface(AutoCloseable::class)
                } else {
                    TypeSpec.classBuilder(className)
                        .addModifiers(KModifier.SEALED) // 網羅性チェックを可能にする
                        .addSuperinterface(AutoCloseable::class)
                }
            }
        }

        // 2. 基本構成（コンストラクタ、segment, isBorrowed, aliveFlag 等）の追加
        StructureGenerator.buildBase(classBuilder, meta)

        // 3. Companion Object の構築 (Handle 解決とレイアウト解決)
        val companionBuilder = TypeSpec.companionObjectBuilder()
        CompanionGenerator.generateCompanions(companionBuilder, meta)

        // 4. メソッドの生成 (downcallHandle を通じた Rust 関数呼び出し)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta)

        // 5. プロパティ / バリアントの生成
        when (meta) {
            is XrossDefinition.Struct -> {
                PropertyGenerator.generateFields(classBuilder, meta)
            }

            is XrossDefinition.Enum -> {
                // EnumVariantGenerator 内で sealed class のサブクラスおよび Factory 関数を生成
                EnumVariantGenerator.generateVariants(classBuilder, meta)
            }
        }

        // 6. 仕上げ
        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        writeToDisk(classBuilder.build(), targetPackage, className, outputDir)
    }

    private fun collectOpaqueTypes(meta: XrossDefinition) {
        meta.methods.forEach { m ->
            (m.args.map { it.ty } + m.ret).filterIsInstance<XrossType.Object>().forEach {
                opaqueObjects.add(it.signature)
            }
        }
    }

    /**
     * 定義が見つからなかった Object 型を、最小限のラッパーとして生成
     */
    fun generateOpaqueWrappers(outputDir: File) {
        opaqueObjects.forEach { signature ->
            val lastDot = signature.lastIndexOf('.')
            val pkg = if (lastDot != -1) signature.substring(0, lastDot) else "org.xross.generated"
            val name = signature.substring(lastDot + 1)

            val wrapper = TypeSpec.classBuilder(name)
                .addModifiers(KModifier.OPEN)
                .addSuperinterface(AutoCloseable::class)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("segment", MemorySegment::class)
                        .addParameter(ParameterSpec.builder("isBorrowed", Boolean::class).defaultValue("true").build())
                        .build()
                )
                .addProperty(
                    PropertySpec.builder("segment", MemorySegment::class, KModifier.PROTECTED)
                        .initializer("segment")
                        .mutable()
                        .build()
                )
                .addFunction(
                    FunSpec.builder("close")
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement("// Opaque types usually managed by native owners")
                        .build()
                )
                .build()

            writeToDisk(wrapper, pkg, name, outputDir)
        }
    }

    private fun writeToDisk(typeSpec: TypeSpec, pkg: String, name: String, outputDir: File) {
        val fileSpec = FileSpec.builder(pkg, name)
            .addType(typeSpec)
            .build()

        // 1. 文字列として書き出す
        var content = fileSpec.toString()

        // 2. 冗長な "public " 修飾子を削除 (class, interface, fun, val, var, object, sealed)
        // Kotlin では public はデフォルトなので削除しても動作は変わりません
        val redundantKeywords =
            listOf(
                "class",
                "interface",
                "fun",
                "val",
                "var",
                "object",
                "sealed",
                "constructor",
                "data class",
                "companion"
            )
        redundantKeywords.forEach { keyword ->
            content = content.replace("public $keyword", keyword)
        }

        // 4. ディレクトリ作成と書き込み
        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()

        fileDir.resolve("$name.kt").writeText(content)
    }
}
