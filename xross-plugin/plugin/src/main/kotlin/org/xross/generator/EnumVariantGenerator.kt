package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import java.lang.foreign.MemorySegment

object EnumVariantGenerator {
    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum
    ) {
        val baseClassName = ClassName(meta.packageName.ifEmpty { "org.example" }, meta.name)

        meta.variants.forEach { variant ->
            val isObject = variant.fields.isEmpty()

            val variantTypeBuilder = if (isObject) {
                // 引数がない場合は object
                TypeSpec.objectBuilder(variant.name)
            } else {
                // 引数がある場合は class
                TypeSpec.classBuilder(variant.name)
            }

            variantTypeBuilder.superclass(baseClassName)

            if (isObject) {
                // object の場合、引数なしでスーパークラスを初期化
                variantTypeBuilder.addSuperclassConstructorParameter(
                    "(new_${variant.name}Handle.invokeExact() as %T).reinterpret(STRUCT_SIZE)",
                    MemorySegment::class
                )
                variantTypeBuilder.addSuperclassConstructorParameter("false")
            } else {
                // class の場合、プライマリコンストラクタで引数を受け取る
                val constructorBuilder = FunSpec.constructorBuilder()
                variant.fields.forEach { field ->
                    constructorBuilder.addParameter(field.name.toCamelCase(), field.ty.kotlinType)
                }

                val argsList = variant.fields.joinToString { it.name.toCamelCase() }

                variantTypeBuilder.primaryConstructor(constructorBuilder.build())
                    .addSuperclassConstructorParameter(
                        "(new_${variant.name}Handle.invokeExact($argsList) as %T).reinterpret(STRUCT_SIZE)",
                        MemorySegment::class
                    )
                    .addSuperclassConstructorParameter("false")

                // フィールドの Getter 定義
                variant.fields.forEach { field ->
                    val vhName = "VH_${variant.name}_${field.name.toCamelCase()}"
                    val prop = PropertySpec.builder(field.name.toCamelCase(), field.ty.kotlinType)
                        .getter(
                            FunSpec.getterBuilder()
                                .addStatement("return $vhName.get(segment, 0L) as %T", field.ty.kotlinType)
                                .build()
                        )
                        .build()
                    variantTypeBuilder.addProperty(prop)
                }
            }

            classBuilder.addType(variantTypeBuilder.build())
        }

        // タグ取得プロパティをベースに追加
        classBuilder.addProperty(
            PropertySpec.builder("tag", Int::class)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return get_tagHandle.invokeExact(segment) as Int")
                        .build()
                )
                .build()
        )
    }
}
