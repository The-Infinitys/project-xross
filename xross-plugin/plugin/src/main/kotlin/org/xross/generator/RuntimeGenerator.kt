package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

/**
 * Generates the common runtime components for Xross in Kotlin.
 */
object RuntimeGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    private val CLEANABLE = ClassName("java.lang.ref.Cleaner", "Cleanable")

    private fun TypeSpec.Builder.addStringBase(memorySegment: TypeName): TypeSpec.Builder = this.primaryConstructor(
        FunSpec.constructorBuilder()
            .addParameter("segment", memorySegment)
            .build(),
    )
        .addProperty(PropertySpec.builder("segment", memorySegment).initializer("segment").build())
        .addProperty(
            PropertySpec.builder("ptr", memorySegment)
                .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.ADDRESS, 0L)").build())
                .build(),
        )
        .addProperty(
            PropertySpec.builder("len", Long::class)
                .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_LONG, 8L)").build())
                .build(),
        )

    fun generate(outputDir: File, basePackage: String) {
        val pkg = "$basePackage.xross.runtime"

        // --- XrossException ---
        val xrossException = TypeSpec.classBuilder("XrossException")
            .superclass(Throwable::class)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("error", Any::class).build())
            .addProperty(PropertySpec.builder("error", Any::class).initializer("error").build())
            .build()

        // --- AliveFlag ---
        val aliveFlag = TypeSpec.classBuilder("AliveFlag")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("initial", Boolean::class)
                    .addParameter(
                        ParameterSpec.builder("parent", ClassName(pkg, "AliveFlag").copy(nullable = true))
                            .defaultValue("null").build(),
                    )
                    .addParameter(ParameterSpec.builder("isPersistent", Boolean::class).defaultValue("false").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "parent",
                    ClassName(pkg, "AliveFlag").copy(nullable = true),
                    KModifier.PRIVATE,
                ).initializer("parent").build(),
            )
            .addProperty(
                PropertySpec.builder("isPersistent", Boolean::class, KModifier.INTERNAL).initializer("isPersistent")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "_isValid",
                    ClassName("java.util.concurrent.atomic", "AtomicBoolean"),
                    KModifier.PRIVATE,
                ).initializer("java.util.concurrent.atomic.AtomicBoolean(initial)").build(),
            )
            .addProperty(
                PropertySpec.builder("isValid", Boolean::class)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return (isPersistent || _isValid.get()) && (parent?.isValid ?: true)")
                            .build(),
                    )
                    .build(),
            )
            .addFunction(FunSpec.builder("invalidate").addStatement("if (!isPersistent) _isValid.set(false)").build())
            .addFunction(
                FunSpec.builder("tryInvalidate")
                    .returns(Boolean::class)
                    .addCode("if (isPersistent) return false\nreturn _isValid.compareAndSet(true, false)\n")
                    .build(),
            )
            .build()

        // --- XrossObject Interface ---
        val xrossObject = TypeSpec.interfaceBuilder("XrossObject")
            .addSuperinterface(AutoCloseable::class)
            .addProperty(PropertySpec.builder("segment", MEMORY_SEGMENT).build())
            .addProperty(PropertySpec.builder("aliveFlag", ClassName(pkg, "AliveFlag")).build())
            .addFunction(FunSpec.builder("relinquish").build())
            .build()

        // --- XrossNativeObject Base Class ---
        val xrossNativeObject = TypeSpec.classBuilder("XrossNativeObject")
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(ClassName(pkg, "XrossObject"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addParameter("arena", Arena::class)
                    .addParameter("aliveFlag", ClassName(pkg, "AliveFlag"))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.OVERRIDE).initializer("segment").build(),
            )
            .addProperty(PropertySpec.builder("arena", Arena::class, KModifier.INTERNAL).initializer("arena").build())
            .addProperty(
                PropertySpec.builder("aliveFlag", ClassName(pkg, "AliveFlag"), KModifier.OVERRIDE)
                    .initializer("aliveFlag").build(),
            )
            .addProperty(
                PropertySpec.builder("cleanable", CLEANABLE.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("if (aliveFlag.isPersistent) null else %T.registerCleaner(this, arena, aliveFlag)", ClassName(pkg, "XrossRuntime")).build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addStatement("cleanable?.clean()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("relinquish")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addStatement("aliveFlag.invalidate()")
                    .build(),
            )
            .build()

        // --- XrossRuntime ---
        val xrossRuntime = TypeSpec.objectBuilder("XrossRuntime")
            .addProperty(
                PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE)
                    .initializer("java.lang.ref.Cleaner.create()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("ofSmart")
                    .returns(Arena::class)
                    .addKdoc("Returns an Arena managed by GC.")
                    .addCode("return %T.ofShared()", Arena::class)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerCleaner")
                    .addParameter("target", Any::class)
                    .addParameter("arena", Arena::class)
                    .addParameter("flag", ClassName(pkg, "AliveFlag"))
                    .returns(CLEANABLE)
                    .addCode(
                        "return CLEANER.register(target) {\n" +
                            "    if (flag.tryInvalidate()) {\n" +
                            "        try { arena.close() } catch (e: Throwable) {}\n" +
                            "    }\n" +
                            "}\n",
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringValue")
                    .addParameter("s", String::class)
                    .returns(ByteArray::class.asTypeName().copy(nullable = true))
                    .addCode("return try { val field = String::class.java.getDeclaredField(\"value\"); field.setAccessible(true); field.get(s) as? ByteArray } catch (e: Throwable) { null }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringCoder")
                    .addParameter("s", String::class)
                    .returns(Byte::class)
                    .addCode("return try { val field = String::class.java.getDeclaredField(\"coder\"); field.setAccessible(true); field.get(s) as? Byte ?: 0.toByte() } catch (e: Throwable) { 0.toByte() }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeDrop")
                    .addParameter("handle", MethodHandle::class)
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addCode(
                        "try {\n" +
                            "    if (handle.type().returnType() == java.lang.Void.TYPE && handle.type().parameterCount() == 2) {\n" +
                            "        java.lang.foreign.Arena.ofConfined().use { arena ->\n" +
                            "            val outPanic = arena.allocate(16)\n" +
                            "            handle.invoke(outPanic, segment)\n" +
                            "        }\n" +
                            "    } else {\n" +
                            "        handle.invoke(segment)\n" +
                            "    }\n" +
                            "} catch (e: Throwable) { e.printStackTrace() }\n",
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("resolveFieldSegment")
                    .addParameter("parent", MEMORY_SEGMENT)
                    .addParameter("vh", java.lang.invoke.VarHandle::class.asClassName().copy(nullable = true))
                    .addParameter("offset", Long::class)
                    .addParameter("size", Long::class)
                    .addParameter("isOwned", Boolean::class)
                    .returns(MEMORY_SEGMENT)
                    .addCode(
                        "if (parent == %T.NULL) return %T.NULL\n" +
                            "return if (isOwned) {\n" +
                            "    parent.asSlice(offset, size)\n" +
                            "} else {\n" +
                            "    if (vh == null) return %T.NULL\n" +
                            "    val ptr = vh.get(parent, offset) as %T\n" +
                            "    if (ptr == %T.NULL) %T.NULL else ptr.reinterpret(size)\n" +
                            "}\n",
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                    )
                    .build(),
            )
            .build()

        // --- XrossAsync ---
        val xrossAsync = TypeSpec.objectBuilder("XrossAsync")
            .addFunction(
                FunSpec.builder("awaitFuture")
                    .addModifiers(KModifier.SUSPEND)
                    .addTypeVariable(TypeVariableName("T"))
                    .addParameter("taskPtr", MEMORY_SEGMENT)
                    .addParameter("pollFn", MethodHandle::class)
                    .addParameter("dropFn", MethodHandle::class)
                    .addParameter(
                        "mapper",
                        LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = TypeVariableName("T")),
                    )
                    .returns(TypeVariableName("T"))
                    .addCode(
                        "try {\n" +
                            "    java.lang.foreign.Arena.ofConfined().use { arena ->\n" +
                            "        while (true) {\n" +
                            "            val resultRaw = pollFn.invokeExact(arena as java.lang.foreign.SegmentAllocator, taskPtr) as MemorySegment\n" +
                            "            val isOk = resultRaw.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()\n" +
                            "            val ptr = resultRaw.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)\n" +
                            "            if (ptr != MemorySegment.NULL) {\n" +
                            "                if (!isOk) {\n" +
                            "                    val errXs = XrossString(ptr.reinterpret(24))\n" +
                            "                    throw XrossException(errXs.toString())\n" +
                            "                }\n" +
                            "                return mapper(ptr)\n" +
                            "            }\n" +
                            "            kotlinx.coroutines.delay(1)\n" +
                            "        }\n" +
                            "    }\n" +
                            "} finally {\n" +
                            "    dropFn.invoke(taskPtr)\n" +
                            "}\n",
                    )
                    .build(),
            )
            .build()

        // --- XrossAsyncLock ---
        val xrossAsyncLock = TypeSpec.classBuilder("XrossAsyncLock")
            .addProperty(
                PropertySpec.builder("rw", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock"))
                    .initializer("java.util.concurrent.locks.ReentrantReadWriteLock(true)")
                    .addModifiers(KModifier.PRIVATE).build(),
            )
            .addFunction(
                FunSpec.builder("lockRead").addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.readLock().tryLock()) { kotlinx.coroutines.delay(1) }").build(),
            )
            .addFunction(FunSpec.builder("unlockRead").addCode("rw.readLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockWrite").addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.writeLock().tryLock()) { kotlinx.coroutines.delay(1) }").build(),
            )
            .addFunction(FunSpec.builder("unlockWrite").addCode("rw.writeLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockReadBlocking").addCode("rw.readLock().lock()").build(),
            )
            .addFunction(FunSpec.builder("unlockReadBlocking").addCode("rw.readLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockWriteBlocking").addCode("rw.writeLock().lock()").build(),
            )
            .addFunction(FunSpec.builder("unlockWriteBlocking").addCode("rw.writeLock().unlock()").build())
            .build()

        val toStringBody = CodeBlock.builder()
            .add(
                "if (ptr == %T.NULL || len == 0L) return \"\"\n" +
                    "val bytes = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE)\n" +
                    "return String(bytes, java.nio.charset.StandardCharsets.UTF_8)\n",
                MEMORY_SEGMENT,
            ).build()

        // --- XrossString ---
        val xrossString = TypeSpec.classBuilder("XrossString")
            .addStringBase(MEMORY_SEGMENT)
            .addProperty(
                PropertySpec.builder("cap", Long::class)
                    .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_LONG, 16L)").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toString")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(String::class)
                    .addCode(toStringBody)
                    .build(),
            )
            .build()

        // --- XrossStringView ---
        val xrossStringView = TypeSpec.classBuilder("XrossStringView")
            .addStringBase(MEMORY_SEGMENT)
            .addProperty(
                PropertySpec.builder("encoding", Byte::class)
                    .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_BYTE, 16L)").build())
                    .build(),
            )
            .build()

        val file = FileSpec.builder(pkg, "XrossRuntime")
            .addImport("java.util.concurrent.atomic", "AtomicBoolean")
            .addImport("java.util.concurrent.locks", "ReentrantReadWriteLock")
            .addImport("java.lang.foreign", "ValueLayout", "SegmentAllocator", "Arena", "Linker", "SymbolLookup", "FunctionDescriptor")
            .addType(xrossException)
            .addType(aliveFlag)
            .addType(xrossObject)
            .addType(xrossNativeObject)
            .addType(xrossRuntime)
            .addType(xrossAsync)
            .addType(xrossAsyncLock)
            .addType(xrossString)
            .addType(xrossStringView)
            .build()

        GeneratorUtils.writeToDisk(file, outputDir)
    }
}
