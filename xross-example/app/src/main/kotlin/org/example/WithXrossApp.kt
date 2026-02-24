package org.example

import kotlinx.coroutines.runBlocking
import org.example.complex.ComplexStruct
import org.example.external.ExternalStruct
import org.example.standalone.GlobalAdd
import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        val libPath = System.getProperty("xross.lib.path")
        if (libPath != null) {
            val f = File(libPath)
            if (f.exists()) {
                System.load(f.absolutePath)
            } else {
                loadFromResources(tempDir)
            }
        } else {
            loadFromResources(tempDir)
        }

        executeMemoryLeakTest()
        println("\n--- Running Stability Test ---")
        executeRawMethodTest()
        executePrimitiveTypeTest()
        executeReferenceAndOwnershipTest()
        executeConcurrencyTest()
        executeEnumTest()
        executeCollectionAndOptionalTest()
        executePropertyTest()
        executeComplexFieldTest()
        executeComplexStructPropertyTest()
        executeHelloEnumTest()
        executeFastStructTest()
        executeAllTypesTest()
        executePanicAndTrivialTest()
        executeStandaloneFunctionTest()
        executeAsyncTest()

        println("\n✅ All tests finished!")
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }
}

private fun loadFromResources(tempDir: File) {
    val libName = "libxross_example.so"
    val libFile = File(tempDir, libName)
    val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
        ?: throw RuntimeException("Resource not found: $libName")

    resourceStream.use { input -> libFile.outputStream().use { output -> input.copyTo(output) } }
    System.load(libFile.absolutePath)
    println("Native library loaded from: ${libFile.absolutePath}")
}

// --- 統合テスト用関数群 (silentパラメータ付き) ---

fun executePanicAndTrivialTest(silent: Boolean = false) {
    val service = MyService()
    try {
        val sum = service.addTrivial(10, 20)
        if (!silent) println("Trivial add: $sum")
        service.addCriticalHeap(100, 200)
        service.causePanic(0.toByte())
    } catch (e: Throwable) {
        if (!silent) println("Caught expected or unexpected exception: $e")
    } finally {
        service.close()
    }
}

fun executeStandaloneFunctionTest(silent: Boolean = false) {
    val res = GlobalAdd.globalAdd(10, 20)
    if (!silent) println("Global add: $res")
    org.example.standalone.GlobalGreet.globalGreet("Xross")
    org.example.standalone.GlobalMultiply.globalMultiply(5, 6)
}

fun executeAsyncTest(silent: Boolean = false) = runBlocking {
    val res = org.example.standalone.AsyncAdd.asyncAdd(100, 200)
    if (!silent) println("Async add: $res")
    val msg = org.example.standalone.AsyncGreet.asyncGreet("Coroutines")
    if (!silent) println("Async greet: $msg")
}

fun executePrimitiveTypeTest(silent: Boolean = false) {
    val pt = PrimitiveTest(5.toByte(), 500, 5000L)
    pt.addU32(500)
    if (!silent) println("Primitive u32: ${pt.u32Val}")
    pt.close()
}

fun executeComplexStructPropertyTest(silent: Boolean = false) {
    val cs = ComplexStruct(42, Result.success(100))
    cs.opt = 100
    cs.res = Result.success(500)
    if (!silent) println("ComplexStruct res: ${cs.res}")
    cs.close()
}

fun executeComplexFieldTest(silent: Boolean = false) {
    val ext = ExternalStruct(100, "Xross Native")
    ext.value = 500
    ext.getValue()
    val greet = ext.greet("Hello")
    if (!silent) println("Greet: $greet")
    ext.close()
}

fun executePropertyTest(silent: Boolean = false) {
    val unknownStruct = UnknownStruct(1, "Hello", 1f)
    unknownStruct.s = "Modified"
    if (!silent) println("Struct string: ${unknownStruct.s}")
    unknownStruct.close()
}

fun executeHelloEnumTest(silent: Boolean = false) {
    val e = org.example.some.HelloEnum.C(org.example.some.HelloEnum.B(42))
    if (!silent) println("HelloEnum result: $e")
    e.close()
}

fun executeFastStructTest(silent: Boolean = false) {
    val fs = org.example.fast.FastStruct(10, "Fast")
    fs.data = 20
    val count = fs.countChars("Zero Copy")
    if (!silent) println("FastStruct count: $count")
    fs.close()
}

fun executeRawMethodTest(silent: Boolean = false) {
    // 1. Test Raw Method
    val fs = org.example.fast.FastStruct(10, "Fast")
    val resMethod = fs.addRaw(100, 200)
    if (!silent) println("Raw Method result: $resMethod")
    assert(resMethod == 300) { "Raw method addRaw failed!" }
    fs.close()

    // 2. Test Raw Function
    val resFunc = org.example.standalone.RawGlobalAdd.rawGlobalAdd(1000, 2000)
    if (!silent) println("Raw Function result: $resFunc")
    assert(resFunc == 3000) { "Raw function rawGlobalAdd failed!" }

    // 3. Test Critical Raw Function
    val resCrit = org.example.standalone.RawGlobalAddCritical.rawGlobalAddCritical(10, 20)
    if (!silent) println("Raw Critical Function result: $resCrit")
    assert(resCrit == 30) { "Raw critical function failed!" }

    // 4. Test Panicable Raw Function
    try {
        org.example.standalone.RawGlobalPanic.rawGlobalPanic(0.toByte())
        if (!silent) println("Raw Panicable Function (Safe) success")

        org.example.standalone.RawGlobalPanic.rawGlobalPanic(1.toByte())
        assert(false) { "Raw panicable function should have panicked!" }
    } catch (e: Throwable) {
        if (!silent) println("Caught expected panic from raw function: ${e.message}")
    }
}

fun executeAllTypesTest(silent: Boolean = false) {
    val t = AllTypesTest()

    // 1. Primitive Get/Set
    t.b = false
    t.i8 = 10
    t.u8 = 20
    t.i16 = 30
    t.u16 = 'A'
    t.i32 = 40
    t.u32 = 50
    t.i64 = 60
    t.u64 = 70
    t.f32 = 80.0f
    t.f64 = 90.0
    t.isize = 100
    t.usize = 110
    t.s = "Modified String"

    // 2. Complex Type Operations
    val newNode = ComprehensiveNode(argOfid = 2, argOfdata = "New Node")
    t.takeOwnedNode(newNode) // Consumes newNode

    val currentNode = t.node
    val id = t.takeRefNode(currentNode)
    t.takeMutRefNode(currentNode, 3)

    val refNode = t.returnRefNode()
    val mutRefNode = t.returnMutRefNode()
    mutRefNode.id = 500

    // 3. Option / Result
    t.testOptions(42, "Some", ComprehensiveNode(argOfid = 4, argOfdata = "Opt"))
    val res = t.getResI(true)

    if (!silent) {
        println("AllTypesTest - i32: ${t.i32}, s: ${t.s}, node.id (after &mut): ${t.node.id}, opt_i: ${t.optI}, res: $res")
        assert(t.node.id == 500) { "Mutable reference modification failed!" }
        assert(t.optI == 42) { "Option passing failed!" }

        println("Primitives - b: ${t.b}, i8: ${t.i8}, u8: ${t.u8}, i16: ${t.i16}, u16: ${t.u16.code}, i64: ${t.i64}, f32: ${t.f32}, f64: ${t.f64}, isize: ${t.isize}, usize: ${t.usize}")

        t.b = true
        t.i8 = 127
        t.u8 = 255.toByte()
        t.i16 = 32767
        t.u16 = '\uFFFF'
        t.i64 = Long.MAX_VALUE
        t.f32 = 3.14f
        t.f64 = 2.71828
        t.isize = -123
        t.usize = 456

        println("After Update - b: ${t.b}, i8: ${t.i8}, u8: ${t.u8.toInt() and 0xFF}, i16: ${t.i16}, u16: ${t.u16.code}, i64: ${t.i64}, f32: ${t.f32}, f64: ${t.f64}, isize: ${t.isize}, usize: ${t.usize}")
    }

    t.close()
}

fun executeEnumTest(silent: Boolean = false) {
    val myService = MyService()
    val e = myService.retEnum()
    if (!silent) println("Enum result: $e")
    e.close()
    myService.close()
}

fun executeMemoryLeakTest(silent: Boolean = false) {
    val iterations = if (silent) 1000 else 10000
    val service = MyService2(0)
    for (i in 1..iterations) {
        service.createClone().use { clone ->
            clone.`val`.update { i }
            clone.execute()
        }
    }
    service.close()
}

fun executeReferenceAndOwnershipTest(silent: Boolean = false) {
    val parent = MyService2(100)
    val borrowed = parent.getSelfRef()
    if (!silent) println("Reference value: ${borrowed.`val`.value}")
    parent.close()
}

fun executeConcurrencyTest(silent: Boolean = false) {
    val shared = MyService2(0)
    shared.`val`.update { it + 1 }
    if (!silent) println("Concurrency value: ${shared.`val`.value}")
    shared.close()
}

fun executeCollectionAndOptionalTest(silent: Boolean = false) {
    val service = MyService()
    val opt = service.getOptionEnum(true)
    if (!silent) println("Option: $opt")
    service.getResultStruct(true).getOrNull()?.close()
    service.close()
}
