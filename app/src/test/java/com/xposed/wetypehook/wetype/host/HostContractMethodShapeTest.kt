package com.xposed.wetypehook.wetype.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形状锚的判据（[HostContext.classByMethodShapes]）与它的结构校验原语。
 *
 * 剪贴板引擎、动作分发器、翻译条高度流三条契约都改成了「若干条方法形状的交集 + 静态自引用
 * 字段」。这里钉住其中不依赖 DexKit 的部分：形状标签、自引用字段判定，以及「没有 bridge
 * 时绝不瞎猜」这条底线 —— 拿不到 DexKit 就必须返回 null 让名字候选表接手。
 */
class HostContractMethodShapeTest {

    private class Singleton {
        companion object {
            @JvmField
            val INSTANCE = Singleton()
        }
    }

    private class Plain

    @Test
    fun shapeLabelReadsLikeASignature() {
        assertEquals(
            "(String,boolean,boolean)void",
            MethodShape(listOf("java.lang.String", "boolean", "boolean"), "void").label
        )
        assertEquals("(CharSequence)void", MethodShape(listOf("java.lang.CharSequence"), "void").label)
        assertEquals(
            "(boolean)CharSequence",
            MethodShape(listOf("boolean"), "java.lang.CharSequence").label
        )
    }

    @Test
    fun staticSelfFieldIsTheSingletonMarker() {
        assertTrue(hasStaticSelfField(Singleton::class.java))
        assertFalse(hasStaticSelfField(Plain::class.java))
    }

    @Test
    fun refusesToGuessWithoutDexKit() {
        val ctx = HostContext(Plain::class.java.classLoader, null) { null }

        val picked = ctx.classByMethodShapes(
            "engine",
            listOf(
                MethodShape(listOf("boolean"), "java.lang.CharSequence"),
                MethodShape(listOf("java.lang.String", "boolean", "boolean"), "void")
            )
        )

        assertNull(picked)
        assertNull(ctx.winner)
    }
}
