package com.xposed.wetypehook.wetype.host

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 同形候选的「原始方法」判定（[HostContext.uniqueShapePrimitive]）。
 *
 * 宿主 4.0.0 在引擎类上放了两个形状相同的 `(boolean) -> CharSequence`：原始取法与转调它的
 * 门控包装。3.5.4 靠名字候选表命中，4.0.0 重排混淆名后落空，剪贴板搜索因此 fail-closed。
 * 这里钉住判据本身：被同形兄弟调用过的才是原始方法；拿不到调用关系时不许瞎猜。
 */
class HostContractShapePrimitiveTest {

    private class Engine {
        fun original(flag: Boolean): CharSequence = if (flag) "pending" else ""

        fun wrapped(flag: Boolean): CharSequence = original(flag)
    }

    private class Single {
        fun only(flag: Boolean): CharSequence = ""
    }

    private val shape: Method.() -> Boolean = {
        parameterTypes.size == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
            CharSequence::class.java.isAssignableFrom(returnType)
    }

    private fun context() = HostContext(Engine::class.java.classLoader, null) { null }

    @Test
    fun picksTheCandidateInvokedByItsSibling() {
        val ctx = context()
        val callers = mapOf("original" to setOf("wrapped"), "wrapped" to emptySet<String>())

        val picked = ctx.uniqueShapePrimitive("pending", Engine::class.java, shape) {
            callers[it.name].orEmpty()
        }

        assertEquals("original", picked?.name)
        assertEquals("shape-primitive:Engine#original", ctx.winner)
    }

    @Test
    fun keepsUniqueShapeMatch() {
        val ctx = context()

        val picked = ctx.uniqueShapePrimitive("pending", Single::class.java, shape)

        assertEquals("only", picked?.name)
        assertEquals("shape:Single#only", ctx.winner)
    }

    @Test
    fun refusesToGuessWithoutCallRelation() {
        val ctx = context()

        val picked = ctx.uniqueShapePrimitive("pending", Engine::class.java, shape)

        assertNull(picked)
        assertEquals(1, ctx.notes.size)
    }
}
