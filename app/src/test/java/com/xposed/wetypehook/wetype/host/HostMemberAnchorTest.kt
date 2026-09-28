package com.xposed.wetypehook.wetype.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [pickHostMethod] / [pickHostField] 的语义：形状唯一就不看名字，形状不唯一才退回名字。 */
class HostMemberAnchorTest {

    @Suppress("unused")
    private class Owner {
        fun t0(): Boolean = true

        fun t1(s: String): Boolean = s.isEmpty()

        fun a(s: String): Int = s.length

        fun b(s: String): Int = s.length

        val x: Int = 1
        val y: Long = 2L
        val z: Long = 3L
    }

    @Test
    fun uniqueShapeWinsWithoutLookingAtNames() {
        val m = pickHostMethod(Owner::class.java, "doesNotExist") { it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType }
        assertEquals("t0", m?.name)
    }

    @Test
    fun ambiguousShapeFallsBackToNames() {
        val m = pickHostMethod(Owner::class.java, "b") { it.parameterTypes.size == 1 && it.returnType == Int::class.javaPrimitiveType }
        assertEquals("b", m?.name)
    }

    @Test
    fun namesAreTriedInOrder() {
        val m = pickHostMethod(Owner::class.java, "nope", "t0") { it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType }
        assertEquals("t0", m?.name)
    }

    @Test
    fun nothingMatchesReturnsNull() {
        assertNull(pickHostMethod(Owner::class.java, "nope") { it.parameterTypes.size == 3 })
    }

    @Test
    fun uniqueFieldShapeWins() {
        val f = pickHostField(Owner::class.java, "doesNotExist") { it.type == Int::class.javaPrimitiveType }
        assertEquals("x", f?.name)
    }

    @Test
    fun ambiguousFieldShapeFallsBackToNames() {
        val f = pickHostField(Owner::class.java, "z") { it.type == Long::class.javaPrimitiveType }
        assertEquals("z", f?.name)
    }
}
