package com.xposed.wetypehook.wetype.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glide 图片链与临时路径工具的结构化解析。
 *
 * 这两处此前都写死了宿主的混淆短名，4.0.0 的 R8 把 `M0/H0/R0` 重排成 `L0/G0/Q0`、
 * 把 `utils.x1` 换成了另一个类，写死就会整条链失效。
 *
 * 假类刻意按宿主的真实形态搭：
 * - 启动对（`submit()` / `submit(int,int)`）在 R8 下拿到的是**两个不同的短名**，所以判据
 *   只能靠「无参返回接口 + 同族存在 `(int,int)` 同返回类型」配对，不能按名字配对；
 * - 宿主自己继承了 RequestBuilder（`utils.t extends com.bumptech.glide.l`），由父类声明的
 *   方法返回的是**父类型**，判据必须容忍这一点；
 * - 构建器的转码类型只放在一个 `Class` 字段里，且字段名与 Glide 的不同，判据必须靠字段类型。
 */
class WeTypeGlideChainTest {

    // ---- 假的 RequestListener：两个方法，5 参 / 4 参，都返回 boolean ----

    private interface FakeRequestListener {
        fun onResourceReady(
            resource: Any?,
            model: Any?,
            target: Any?,
            dataSource: Any?,
            isFirstResource: Boolean
        ): Boolean

        fun onLoadFailed(
            exception: Any?,
            model: Any?,
            target: Any?,
            isFirstResource: Boolean
        ): Boolean
    }

    private interface FakeFuture

    private interface FakeTarget

    private interface FakeRequest

    /** 假的 BaseRequestOptions：一批无参方法，返回自身（父类型）或接口，用来验证不会被误选。 */
    private abstract class FakeOptions {
        fun override(width: Int): FakeOptions = this
        fun override(width: Int, height: Int): FakeOptions = this
        fun centerCrop(): FakeOptions = this
        fun placeholder(resId: Int): FakeOptions = this
        fun headers(): Map<String, String> = emptyMap()
    }

    /** 模仿 `com.bumptech.glide.l`：启动对的两个短名不同，载入 / 监听返回的是父类型。 */
    private abstract class FakeBuilderBase(
        @JvmField val transcodeClass: Class<*>
    ) : FakeOptions() {
        fun l0(url: String): FakeBuilderBase = this
        fun l0(bytes: ByteArray): FakeBuilderBase = this
        fun g0(listener: FakeRequestListener?): FakeBuilderBase = this
        fun q0(): FakeTarget = object : FakeTarget {}
        fun r0(width: Int, height: Int): FakeTarget = object : FakeTarget {}
        fun s0(): FakeFuture = object : FakeFuture {}
        fun t0(width: Int, height: Int): FakeFuture = object : FakeFuture {}
        fun y0(): FakeBuilderBase = this
    }

    /** 模仿宿主自己的 `utils.t extends com.bumptech.glide.l`：本类声明协变返回的重载。 */
    private class FakeBuilder<Transcode>(type: Class<*>) : FakeBuilderBase(type) {
        fun u0(listener: FakeRequestListener?): FakeBuilder<Transcode> = this
        fun e1(): FakeBuilder<Transcode> = this
        fun h1(resId: Int): FakeBuilder<Transcode> = this
    }

    /** 模仿宿主自己的 `utils.v extends com.bumptech.glide.m`：工厂方法返回 `utils.t`。 */
    private class FakeManager {
        fun asDrawable(): FakeBuilder<Any> = FakeBuilder(Any::class.java)
        fun asBitmap(): FakeBuilder<Any> = FakeBuilder(Any::class.java)
        fun asGif(): FakeBuilder<Any> = FakeBuilder(Any::class.java)
        fun asFile(): FakeBuilder<Any> = FakeBuilder(File::class.java)
        fun downloadOnly(): FakeBuilder<Any> = FakeBuilder(File::class.java)
        fun retriever(): FakeRetriever = FakeRetriever()
    }

    /** 只有位图系：没有 File 转码时解析必须放弃，不能挑一个凑数。 */
    private class BitmapOnlyManager {
        fun asBitmap(): FakeBuilder<Any> = FakeBuilder(Any::class.java)
    }

    private class FakeRetriever

    private class FakeContext

    private class FakeGlide {
        companion object {
            @JvmStatic
            fun with(context: FakeContext): FakeManager = FakeManager()

            @JvmStatic
            fun get(context: FakeContext): FakeGlide = FakeGlide()

            @JvmStatic
            fun retriever(context: FakeContext): FakeRetriever = FakeRetriever()
        }
    }

    private class FakeTargetImpl : FakeTarget {
        fun getRequest(): FakeRequest = object : FakeRequest {}
        fun size(): Int = 0
    }

    @Test
    fun `RequestManager 按形状解析, 只认返回 RequestBuilder 的静态方法`() {
        val resolved = WeTypeGlideChain.resolveManager(
            FakeGlide::class.java,
            FakeContext::class.java,
            FakeRequestListener::class.java
        )
        assertNotNull("静态 (Context) -> RequestManager 应当命中", resolved)
        assertEquals("with", resolved!!.name)
        assertEquals(FakeManager::class.java, resolved.returnType)
    }

    @Test
    fun `RequestBuilder 指纹排除不具备 load 与 listener 的类型`() {
        assertTrue(
            WeTypeGlideChain.hasBuilderShape(FakeBuilder::class.java, FakeRequestListener::class.java)
        )
        assertTrue(
            WeTypeGlideChain.hasBuilderFactory(FakeManager::class.java, FakeRequestListener::class.java)
        )
        assertFalse(
            WeTypeGlideChain.hasBuilderShape(FakeRetriever::class.java, FakeRequestListener::class.java)
        )
        assertFalse(
            WeTypeGlideChain.hasBuilderFactory(
                FakeRetriever::class.java,
                FakeRequestListener::class.java
            )
        )
    }

    @Test
    fun `构建器工厂按转码类型挑, 不挑 asBitmap`() {
        val manager = FakeManager()
        val factory = WeTypeGlideChain.resolveBuilderFactory(
            manager,
            FakeRequestListener::class.java,
            File::class.java
        )
        assertNotNull("应当挑到能给出 File 转码的那一个", factory)
        val builder = factory!!.invoke(manager)
        assertTrue(
            "选中的构建器转码类型必须是 File",
            WeTypeGlideChain.transcodeTypes(builder).any { it == File::class.java }
        )
    }

    @Test
    fun `没有 File 转码时返回 null 而不是凑数`() {
        assertNull(
            WeTypeGlideChain.resolveBuilderFactory(
                BitmapOnlyManager(),
                FakeRequestListener::class.java,
                File::class.java
            )
        )
    }

    @Test
    fun `载入认父类声明的单参 String 方法, 不挑字节数组重载`() {
        val load = WeTypeGlideChain.resolveLoad(FakeBuilder::class.java)
        assertNotNull("父类声明的 load(String) 返回父类型也必须算命中", load)
        assertEquals("l0", load!!.name)
        assertEquals(1, load.parameterTypes.size)
        assertEquals(String::class.java, load.parameterTypes[0])
    }

    @Test
    fun `监听按 RequestListener 单参定位`() {
        val listener = WeTypeGlideChain.resolveListener(
            FakeBuilder::class.java,
            FakeRequestListener::class.java
        )
        assertNotNull(listener)
        assertEquals(1, listener!!.parameterTypes.size)
        assertEquals(FakeRequestListener::class.java, listener.parameterTypes[0])
    }

    @Test
    fun `RequestListener 接口由构建器形状反推, 不必先知道接口名`() {
        val derived = WeTypeGlideChain.resolveListenerInterface(FakeBuilder::class.java)
        assertEquals(FakeRequestListener::class.java, derived)
    }

    @Test
    fun `持有器与构建器工厂都能在不知道 RequestListener 时解析`() {
        val manager = WeTypeGlideChain.resolveManager(FakeGlide::class.java, FakeContext::class.java)
        assertNotNull("静态 (Context) -> RequestManager 应当命中", manager)
        assertEquals("不能把 get / retriever 当成 RequestManager", "with", manager!!.name)
        assertEquals(FakeManager::class.java, manager.returnType)

        val factory = WeTypeGlideChain.resolveBuilderFactory(FakeManager(), File::class.java)
        assertNotNull("应当挑到能给出 File 转码的那一个", factory)
        val builder = factory!!.invoke(FakeManager())
        assertTrue(
            "选中的构建器转码类型必须是 File",
            WeTypeGlideChain.transcodeTypes(builder).any { it == File::class.java }
        )
    }

    @Test
    fun `启动按无参返回接口与 int,int 同返回类型配对, 不依赖两个重载同名`() {
        val start = WeTypeGlideChain.resolveStart(FakeBuilder::class.java)
        assertNotNull("R8 下 submit 与 submit(int,int) 短名不同，仍必须命中", start)
        assertEquals(0, start!!.parameterTypes.size)
        assertTrue("启动方法必须返回接口", start.returnType.isInterface)
        assertTrue(
            "只可能是启动对里的那个，不能是 autoClone / centerCrop",
            start.name == "q0" || start.name == "s0"
        )
    }

    @Test
    fun `持有句柄取无参返回接口的那个, 不取 size`() {
        val request = WeTypeGlideChain.resolveRequest(FakeTargetImpl::class.java)
        assertNotNull(request)
        assertTrue(request!!.returnType.isInterface)
    }

    @Test
    fun `临时路径工具只认两条 String 方法加一条无参 String 的那个类`() {
        val picked = WeTypeTempPath.resolve(
            WeTypeGlideChainTest::class.java.classLoader!!,
            arrayOf(
                BINARY_PREFIX + "ReplacementSpanLike",
                BINARY_PREFIX + "MapFieldUtil",
                BINARY_PREFIX + "SingleMethodUtil",
                BINARY_PREFIX + "TempPathUtil"
            )
        )
        assertNotNull("应当跳过前三个，命中 TempPathUtil", picked)
        assertEquals(TempPathUtil::class.java, picked!!.declaringClass)
    }

    /** 占位：4.0.0 的 `utils.x1` 换成了 ReplacementSpan 子类，没有静态 String 工具方法。 */
    @Suppress("unused")
    private class ReplacementSpanLike {
        fun draw(): Int = 0
    }

    /** 占位：4.0.0 的 `utils.j0` 有两条 String 方法，但缺无参 String 方法。 */
    @Suppress("unused")
    private class MapFieldUtil {
        companion object {
            @JvmStatic
            fun a(value: String): String = value

            @JvmStatic
            fun b(value: String): String = value
        }
    }

    /** 占位：4.0.0 的 `utils.y1` 只有一条 String 方法。 */
    @Suppress("unused")
    private class SingleMethodUtil {
        companion object {
            @JvmStatic
            fun a(value: String): String = value
        }
    }

    /** 3.5.4 `utils.x1` / 4.0.0 `utils.w1` 的形状：两条 String 方法 + 一条无参 String。 */
    @Suppress("unused")
    private class TempPathUtil {
        companion object {
            @JvmStatic
            fun a(value: String): String = value

            @JvmStatic
            fun b(value: String): String = value

            @JvmStatic
            fun c(): String = ""
        }
    }

    private companion object {
        const val BINARY_PREFIX = "com.xposed.wetypehook.wetype.hook.WeTypeGlideChainTest\$"
    }
}
