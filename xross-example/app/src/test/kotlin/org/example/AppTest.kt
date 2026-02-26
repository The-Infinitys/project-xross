package org.example

import org.example.complex.ComplexStruct
import org.example.external.ExternalStruct
import org.example.standalone.*
import org.example.test.test2.MyService2
import kotlin.test.*

class AppTest {
    @BeforeTest
    fun setup() {
        NativeLoader.load()
    }

    @Test
    fun testPrimitiveTypes() {
        val pt = PrimitiveTest(5.toByte(), 500, 5000L)
        assertEquals(5.toByte(), pt.u8Val)
        assertEquals(500, pt.u32Val)
        assertEquals(5000L, pt.u64Val)

        pt.addU32(500)
        assertEquals(1000, pt.u32Val)
        pt.close()
    }

    @Test
    fun testMultipleConstructorsAndUse() {
        // 1. Default constructor (via #[xross_default])
        UnknownStruct().use { defaultObj ->
            assertEquals(32, defaultObj.i)
            assertEquals("Hello, World!", defaultObj.s)
        }

        // 2. Specific constructor (via #[xross_new] with_int)
        UnknownStruct(argOfi = 100).use { intObj ->
            assertEquals(100, intObj.i)
            assertEquals("From Int", intObj.s)
        }

        // 3. Companion 'use' function (Automatic resource management)
        val result = UnknownStruct.use {
            assertEquals(32, this.i)
            "Success"
        }
        assertEquals("Success", result)

        // 4. Companion 'useWithInt' function
        UnknownStruct.useWithInt(500) {
            assertEquals(500, this.i)
        }
    }

    @Test
    fun testStringProperties() {
        val unknown = UnknownStruct(1, "Initial", 1.0f)
        assertEquals("Initial", unknown.s)

        unknown.s = "Modified"
        assertEquals("Modified", unknown.s)

        unknown.s = "日本語テスト"
        assertEquals("日本語テスト", unknown.s)
        unknown.close()
    }

    @Test
    fun testConcurrencyAndAtomics() {
        val service = MyService2(100)
        assertEquals(100, service.`val`.value)

        service.`val`.update { it + 50 }
        assertEquals(150, service.`val`.value)

        service.close()
    }

    @Test
    fun testComplexStructs() {
        val cs = ComplexStruct(42, Result.success(100))
        assertEquals(42, cs.opt)

        val r1 = cs.res
        assertTrue(cs.res.isSuccess, "Result should be success")
        assertEquals(100, cs.res.getOrNull())

        cs.opt = null
        assertNull(cs.opt)

        cs.close()
    }

    @Test
    fun testStandaloneFunctions() {
        assertEquals(30, GlobalAdd.globalAdd(10, 20))
        assertEquals("Hello, World!", GlobalGreet.globalGreet("World"))
        assertEquals(20, GlobalMultiply.globalMultiply(4, 5))
    }

    @Test
    fun testPanicHandling() {
        val service = MyService()
        service.causePanic(0.toByte())
        assertFailsWith<org.example.xross.runtime.XrossException> {
            service.causePanic(1.toByte())
        }
        service.close()
    }

    @Test
    fun testArray() {
        // 1. Test Array as field
        ArrayTest(1.23).use { t ->
            val data = t.data
            assertEquals(4, data.size)
            data.forEach { assertEquals(1.23, it) }

            t.data = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
            val updated = t.data
            assertEquals(1.0, updated[0])
            assertEquals(2.0, updated[1])
            assertEquals(3.0, updated[2])
            assertEquals(4.0, updated[3])

            assertEquals(10.0, t.sum())
        }

        // 2. Test Array as return value
        val arr = org.example.arrays.GetFixedArray.getFixedArray()
        assertEquals(8, arr.size)
        for (i in 0 until 8) {
            assertEquals(i + 1, arr[i])
        }

        // 3. Test Array as argument
        val sum = org.example.arrays.SumFixedArray.sumFixedArray(intArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
        assertEquals(8, sum)
    }

    @Test
    fun testExternalStruct() {
        val ext = ExternalStruct(10, "Tester")
        assertEquals(10, ext.value)
        assertEquals("Tester", ext.name)

        ext.value = 20
        assertEquals(20, ext.getValue())

        assertEquals("Hi Tester", ext.greet("Hi"))
        ext.close()
    }
}
