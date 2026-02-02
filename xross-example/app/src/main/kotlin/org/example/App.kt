package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.lang.foreign.MemorySegment
import java.lang.reflect.Field
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        // 1. ネイティブライブラリの準備
        // 環境に合わせて .so / .dll / .dylib を切り替えるロジックがあるとベターですが、ここでは .so 固定
        val libName = "libxross_example.so"
        val libFile = File(tempDir, libName)

        val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
            ?: throw RuntimeException("Resource not found: $libName")

        resourceStream.use { input ->
            libFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // 2. ライブラリのロード
        System.load(libFile.absolutePath)
        println("Native library loaded from: ${libFile.absolutePath}")

        // 3. テストの実行
        executeMemoryLeakTest()
        executeConcurrencyTest()
        executeReferenceAndOwnershipTest()
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
            println("Temporary directory deleted.")
        }
    }
}

fun executeReferenceAndOwnershipTest() {
    println("\n--- Starting Reference & Ownership Test ---")

    // 1. 所有権の消費 (consumeSelf) のテスト
    println("[Test 1] Ownership Consumption")
    val serviceForConsume = MyService()
    val size = serviceForConsume.consumeSelf()
    println("Native Vec size: $size")

    try {
        // すでに consumeSelf() で segment が NULL になっているため、例外が発生するはず
        serviceForConsume.execute(10)
    } catch (e: NullPointerException) {
        println("Success: Caught expected error after consumeSelf: ${e.message}")
    }

    // 2. 借用 (isBorrowed = true) のシミュレーションテスト
    // Rust側から &Self が返ってきた場合、Kotlin側で close しても Rust側の drop は呼ばれないことを確認
    println("\n[Test 2] Borrowed Object (isBorrowed = true)")
    val parentService = MyService()

    // 内部コンストラクタを使用して借用状態のオブジェクトを作成
    // 本来は Rust メソッドの戻り値として生成されるケース
    val borrowedService = MyService(parentService.getRawSegmentForTest(), isBorrowed = true)

    borrowedService.use {
        val res = it.execute(100)
        println("Borrowed execution result: $res")
    } // close() が呼ばれるが isBorrowed=true なので Rust drop は呼ばれない

    try {
        // 親オブジェクトはまだ生きているはず
        val res = parentService.execute(5)
        println("Parent is still alive: $res")
    } catch (e: Exception) {
        println("Failure: Parent should be alive, but caught: ${e.message}")
    }

    // 3. 多重解放の防止
    println("\n[Test 3] Double Close Safety")
    parentService.close()
    parentService.close() // 2回呼んでもクラッシュしないことを確認
    println("Double close handled safely.")

    println("--- Reference & Ownership Test Finished ---")
}

// テスト用に非公開セグメントを取得するための拡張（MyServiceクラス内に定義するか、リフレクションで取得）
// テスト用に非公開の segment フィールドから本物の MemorySegment を取得する
fun MyService.getRawSegmentForTest(): MemorySegment {
    return try {
        val field: Field = MyService::class.java.getDeclaredField("segment")
        field.isAccessible = true
        field.get(this) as MemorySegment
    } catch (e: Exception) {
        throw RuntimeException("Failed to extract segment via reflection", e)
    }
}

/**
 * メモリリークテスト
 * 大量のオブジェクト生成、メソッド呼び出し、文字列取得でRSSが増大し続けないか確認
 */
fun executeMemoryLeakTest() {
    println("\n--- Starting Memory Leak Test ---")

    // パッケージありの MyService2
    val myService2 = MyService2(0)

    val iterations = 100_000
    val reportInterval = iterations / 10

    println("Running iterations: $iterations")

    for (i in 1..iterations) {
        // clone() [ReadLock] を使用して新しいインスタンスを生成
        // createClone() が Rust 側で実装されている場合はそちらでも可
        myService2.createClone().use { clone ->

            // フィールド 'val' は予約語なのでバッククォートでアクセス
            clone.`val` = i

            // mut_test [WriteLock]
            clone.mutTest()

            // execute [ReadLock]
            val result = clone.execute()

            // static method の呼び出し (MyService 側)
            val msg = MyService.strTest()

            if (i % reportInterval == 0) {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                println("Iteration $i: Val=${clone.`val`}, Res=$result, Msg='$msg', JVM Mem: ${usedMem}MB")
            }
        } // ここで Rust 側の drop が呼ばれる
    }

    myService2.close()
    println("--- Memory Leak Test Finished ---")
}

/**
 * 並行性テスト
 * 共有インスタンスに対して複数のスレッドから Read/Write ロックが正しく機能するか確認
 */
fun executeConcurrencyTest() {
    println("\n--- Starting Multi-threaded Concurrency Test ---")

    val sharedService = MyService2(0)
    val threadCount = 8
    val executor = Executors.newFixedThreadPool(threadCount)
    val iterationsPerThread = 5_000
    val totalOps = AtomicInteger(0)

    val start = System.currentTimeMillis()

    repeat(threadCount) { threadId ->
        executor.submit {
            try {
                for (i in 1..iterationsPerThread) {
                    when (i % 4) {
                        0 -> sharedService.execute() // Read
                        1 -> sharedService.mutTest() // Write
                        2 -> sharedService.`val` += 1 // Write (Property)
                        3 -> {
                            // 参照取得テスト (isReference = true)
                            sharedService.getSelfRef().use { ref ->
                                ref.execute()
                            }
                        }
                    }
                    totalOps.incrementAndGet()
                }
            } catch (e: Exception) {
                System.err.println("Thread $threadId error: ${e.message}")
            }
        }
    }

    executor.shutdown()
    executor.awaitTermination(30, TimeUnit.SECONDS)
    val end = System.currentTimeMillis()

    println("--- Concurrency Test Results ---")
    println("Time: ${end - start}ms")
    println("Total Ops: ${totalOps.get()}")
    println("Final Shared Value: ${sharedService.`val`}")

    sharedService.close()

    // 値の整合性チェック (初期0 + (書き込み回数 * スレッド数))
    // 1スレッドにつき i%4 == 1 と i%4 == 2 の 2回書き込み
    val expected = iterationsPerThread * threadCount / 2
    println("Expected Value (approx): $expected")
}
