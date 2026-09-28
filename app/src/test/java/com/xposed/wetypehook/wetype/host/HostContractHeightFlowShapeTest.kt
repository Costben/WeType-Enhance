package com.xposed.wetypehook.wetype.host

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译条高度流两个入口的判据（`clipboard.height.set.text` / `clipboard.height.set.char`）。
 *
 * 宿主 `q` 上 `(CharSequence) -> void` 有两个：编译器生成的静态存取器与真正干活的；`(boolean) -> void`
 * 有五个，排掉 synthetic 还剩四个原始方法 —— 形状挑不出唯一，只能靠方法体内的日志文本。
 * 这里钉住三条：synthetic 必须排除、布尔那一侧形状必须让位给字符串锚、拿不到 DexKit 时回落名字候选。
 */
class HostContractHeightFlowShapeTest {

    /** 形状照宿主 `q` 铺：两条 CharSequence（一条 synthetic）+ 五条 boolean（一条 synthetic）。 */
    private class HeightFlow {
        @JvmSynthetic
        fun accessor(text: CharSequence): Unit = Unit

        fun schedule(text: CharSequence): Unit = Unit

        fun commitA(flag: Boolean): Unit = Unit
        fun commitB(flag: Boolean): Unit = Unit
        fun commitC(flag: Boolean): Unit = Unit
        fun commitD(flag: Boolean): Unit = Unit

        @JvmSynthetic
        fun commitSynthetic(flag: Boolean): Unit = Unit

        fun T(flag: Boolean): Unit = Unit
    }

    private val textShape: Method.() -> Boolean = {
        parameterTypes.size == 1 && parameterTypes[0] == CharSequence::class.java && returnType == Void.TYPE
    }

    private val charShape: Method.() -> Boolean = {
        parameterTypes.size == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType && returnType == Void.TYPE
    }

    private fun context() = HostContext(HeightFlow::class.java.classLoader, null) { id ->
        if (id == HostContractId.CLIPBOARD_HEIGHT_MANAGER) HostHandle(owner = HeightFlow::class.java) else null
    }

    private fun contract(id: String) = HOST_CONTRACTS.first { it.id == id }

    @Test
    fun scheduleShapeNeedsTheSyntheticFilter() {
        val unfiltered = HostContext(HeightFlow::class.java.classLoader, null) { null }
            .uniqueMethod("schedule", HeightFlow::class.java, textShape)
        assertNull("两个同形方法，不过滤 synthetic 就不唯一", unfiltered)

        val filtered = HostContext(HeightFlow::class.java.classLoader, null) { null }
            .uniqueMethod("schedule", HeightFlow::class.java) { textShape() && !isSynthetic }
        assertEquals("schedule", filtered?.name)
    }

    @Test
    fun scheduleContractNoLongerFallsBackToTheObfuscatedName() {
        val ctx = context()

        val handle = contract(HostContractId.CLIPBOARD_HEIGHT_SET_TEXT).resolve(ctx)

        assertEquals("schedule", handle?.method?.name)
        assertNull("这条契约不该再靠写死的名字兜住", ctx.nameFallback)
        assertTrue("应该报成形状命中，而不是 names:", ctx.winner.orEmpty().startsWith("shape:"))
    }

    @Test
    fun commitShapeAloneStaysAmbiguous() {
        val picked = HostContext(HeightFlow::class.java.classLoader, null) { null }
            .uniqueMethod("commit", HeightFlow::class.java) { charShape() && !isSynthetic }
        assertNull("五个同形方法，排掉 synthetic 还剩四个 —— 形状挑不出来", picked)
    }

    @Test
    fun commitContractFallsBackToTheNameCandidateWithoutDexKit() {
        val ctx = context()

        val handle = contract(HostContractId.CLIPBOARD_HEIGHT_SET_CHAR).resolve(ctx)

        assertEquals("T", handle?.method?.name)
        assertEquals(
            "没有 DexKit 时只能回落名字候选，并且要留下退化痕迹",
            "names:HeightFlow#T",
            ctx.nameFallback
        )
    }
}
