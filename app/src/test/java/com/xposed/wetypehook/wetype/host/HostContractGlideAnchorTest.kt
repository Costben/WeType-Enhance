package com.xposed.wetypehook.wetype.host

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glide 入口 / RequestListener 接口、宿主 `k6` 工具类、翻译下拉列表这几条结构判据。
 *
 * 这几处此前只有写死的混淆名（`com.bumptech.glide.c`、`q1.h`、`k6.a`、`k6.e`、
 * `translatingwhilewriting.d`），宿主或库一改短名就整条链失效。判据只吃 `Class` 反射，
 * 所以能在 JVM 单测里钉住；「两版真实 dex 上唯一命中」那半边靠离线 dexdump 核对。
 */
class HostContractGlideAnchorTest {

    // ---- Glide 形状 ----

    /** 真的 RequestListener：两条返回 boolean 的方法，参数 5 / 4。 */
    private interface FakeRequestListener {
        @Suppress("unused")
        fun onResourceReady(a: Any?, b: Any?, c: Any?, d: Any?, e: Boolean): Boolean

        @Suppress("unused")
        fun onLoadFailed(a: Any?, b: Any?, c: Any?, d: Boolean): Boolean
    }

    /** 形状对但多了一条方法 —— 不是 RequestListener。 */
    private interface TooManyMethods {
        @Suppress("unused")
        fun onResourceReady(a: Any?, b: Any?, c: Any?, d: Any?, e: Boolean): Boolean

        @Suppress("unused")
        fun onLoadFailed(a: Any?, b: Any?, c: Any?, d: Boolean): Boolean

        @Suppress("unused")
        fun extra(a: Boolean): Boolean
    }

    /** 方法数对但返回类型不是 boolean。 */
    private interface WrongReturn {
        @Suppress("unused")
        fun a(a: Any?, b: Any?, c: Any?, d: Any?, e: Boolean): Int

        @Suppress("unused")
        fun b(a: Any?, b: Any?, c: Any?, d: Boolean): Boolean
    }

    private interface FakeKey

    private class FakeBuilder {
        @Suppress("unused")
        fun load(url: String): FakeBuilder = this

        @Suppress("unused")
        fun listener(listener: FakeRequestListener?): FakeBuilder = this

        @Suppress("unused")
        fun transform(key: FakeKey?): FakeBuilder = this

        @Suppress("unused")
        fun placeholder(resId: Int): FakeBuilder = this
    }

    private class FakeManager {
        @Suppress("unused")
        fun asFile(): FakeBuilder = FakeBuilder()

        @Suppress("unused")
        fun retriever(): String = ""
    }

    private class FakeGlide {
        @Suppress("unused")
        fun instanceMethod(): Int = 0

        companion object {
            @JvmField
            val INSTANCE = FakeGlide()

            @JvmStatic
            fun with(context: Context): FakeManager = FakeManager()

            @JvmStatic
            fun get(context: Context): FakeGlide = FakeGlide()

            @JvmStatic
            fun retriever(context: Context): String = ""
        }
    }

    /** 有静态 `(Context) -> RequestManager`，但没有指向自身的静态字段。 */
    private class NoSelfField {
        companion object {
            @JvmStatic
            fun with(context: Context): FakeManager = FakeManager()
        }
    }

    @Test
    fun `RequestListener 只认两条 boolean 方法且参数 5 与 4`() {
        assertTrue(GlideShape.looksLikeRequestListener(FakeRequestListener::class.java))
        assertFalse(GlideShape.looksLikeRequestListener(TooManyMethods::class.java))
        assertFalse(GlideShape.looksLikeRequestListener(WrongReturn::class.java))
        assertFalse(GlideShape.looksLikeRequestListener(FakeKey::class.java))
        assertFalse("类不是接口就不算", GlideShape.looksLikeRequestListener(FakeBuilder::class.java))
    }

    @Test
    fun `RequestBuilder 要同时能 load 与 listener`() {
        assertTrue(GlideShape.looksLikeRequestBuilder(FakeBuilder::class.java))
        assertFalse("只有单参接口方法、没有 load(String)", GlideShape.looksLikeRequestBuilder(FakeKey::class.java))
        assertFalse(GlideShape.looksLikeRequestBuilder(FakeManager::class.java))
    }

    @Test
    fun `RequestManager 认无参方法返回 RequestBuilder`() {
        assertTrue(GlideShape.looksLikeRequestManager(FakeManager::class.java))
        assertFalse(GlideShape.looksLikeRequestManager(FakeBuilder::class.java))
        assertFalse(GlideShape.looksLikeRequestManager(FakeGlide::class.java))
    }

    @Test
    fun `Glide 入口认静态自引用字段加静态 Context 入口`() {
        assertTrue(GlideShape.looksLikeGlideEntry(FakeGlide::class.java, Context::class.java))
        assertFalse(
            "没有静态自引用字段",
            GlideShape.looksLikeGlideEntry(NoSelfField::class.java, Context::class.java)
        )
        assertFalse(
            "入口参数不是 Context 时不算",
            GlideShape.looksLikeGlideEntry(FakeGlide::class.java, String::class.java)
        )
    }

    // ---- 宿主 k6 工具类 ----

    private class FakeCrypto {
        companion object {
            @JvmStatic
            fun a(data: ByteArray, key: String): ByteArray = data

            @JvmStatic
            fun b(key: String, input: String, output: String): Boolean = true
        }
    }

    /** 缺 `(String,String,String) -> boolean` 那条解密入口。 */
    private class CryptoMissingDecrypt {
        companion object {
            @JvmStatic
            fun a(data: ByteArray, key: String): ByteArray = data
        }
    }

    private class FakeHash {
        companion object {
            @JvmStatic
            fun a(file: File): String = ""

            @JvmStatic
            fun b(file: File, size: Int): String = ""

            @JvmStatic
            fun d(path: String): String = ""
        }
    }

    /** 缺 `(String) -> String` 那条按路径取摘要的入口。 */
    private class HashMissingPathEntry {
        companion object {
            @JvmStatic
            fun a(file: File): String = ""

            @JvmStatic
            fun b(file: File, size: Int): String = ""
        }
    }

    @Test
    fun `文件解密工具要两条静态形状同时具备`() {
        assertTrue(looksLikeFileCrypto(FakeCrypto::class.java))
        assertFalse(looksLikeFileCrypto(CryptoMissingDecrypt::class.java))
        assertFalse(looksLikeFileCrypto(FakeHash::class.java))
    }

    @Test
    fun `文件摘要工具要三条静态形状同时具备`() {
        assertTrue(looksLikeFileHash(FakeHash::class.java))
        assertFalse(looksLikeFileHash(HashMissingPathEntry::class.java))
        assertFalse(looksLikeFileHash(FakeCrypto::class.java))
    }

    // ---- 翻译下拉列表 ----

    private class FakeDropdownList : androidx.recyclerview.widget.RecyclerView()

    private class FakeLayoutManager : androidx.recyclerview.widget.RecyclerView.LayoutManager()

    @Test
    fun `下拉列表只认直接继承 RecyclerView 的类`() {
        assertTrue(looksLikeDropdownList(FakeDropdownList::class.java))
        assertFalse(
            "父类是 RecyclerView 的内嵌子类（LayoutManager）不算",
            looksLikeDropdownList(FakeLayoutManager::class.java)
        )
        assertFalse(looksLikeDropdownList(FakeBuilder::class.java))
    }
}
