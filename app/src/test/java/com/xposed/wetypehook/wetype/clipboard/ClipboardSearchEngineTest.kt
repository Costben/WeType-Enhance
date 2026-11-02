package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ClipboardSearchEngineTest {

    @Test
    fun emptyKeywordReturnsFirstLimitWithEmptyRanges() {
        val contents = listOf("hello", "微信输入法", "clipboard")
        val res = ClipboardSearchEngine.search(contents, "", limit = 2)
        assertEquals(2, res.size)
        assertEquals(0, res[0].index)
        assertEquals(1, res[1].index)
        assertTrue(res.all { it.ranges.isEmpty() })
        assertTrue(ClipboardSearchEngine.matches("任意内容", ""))
    }

    @Test
    fun caseInsensitiveContains() {
        assertTrue(ClipboardSearchEngine.matches("Hello World", "hello"))
        assertTrue(ClipboardSearchEngine.matches("Hello World", "HELLO"))
        assertTrue(ClipboardSearchEngine.matches("Hello World", "HeLlO"))
        assertFalse(ClipboardSearchEngine.matches("Hello World", "bye"))
        val res = ClipboardSearchEngine.search(listOf("Hello World"), "hello")
        assertEquals(1, res.size)
        assertEquals(listOf(0..4), res[0].ranges)
    }

    @Test
    fun literalMatchReturnsEveryOccurrenceNotJustTheFirst() {
        // 卡片高亮是逐段染色的：内核只回第一处命中，同一张卡里后面的命中就永远不标红
        // （实机：搜 `SS` 只有开头那一处变色）。三段互不重叠的命中都要给出来。
        val res = ClipboardSearchEngine.search(listOf("ss-a-ss-b-ss"), "ss")
        assertEquals(1, res.size)
        assertEquals(listOf(0..1, 5..6, 10..11), res[0].ranges)
    }

    @Test
    fun chineseLiteralContains() {
        assertTrue(ClipboardSearchEngine.matches("微信输入法剪贴板", "输入法"))
        assertFalse(ClipboardSearchEngine.matches("微信输入法剪贴板", "搜狗"))
        val res = ClipboardSearchEngine.search(listOf("微信输入法剪贴板"), "输入法")
        assertEquals(1, res.size)
        assertEquals(listOf(2..4), res[0].ranges)
    }

    @Test
    fun fullPinyinMatch() {
        // "中国" -> ZHONG GUO，全拼包含命中。
        assertTrue(ClipboardSearchEngine.matches("中国北京", "zhongguo"))
        assertTrue(ClipboardSearchEngine.matches("中国北京", "ZHONGGUO"))
        assertFalse(ClipboardSearchEngine.matches("中国北京", "meiguo"))
        val res = ClipboardSearchEngine.search(listOf("abc中国北京def"), "zhongguo")
        assertEquals(1, res.size)
        assertEquals(0, res[0].index)
        assertTrue(res[0].ranges.isNotEmpty())
        // 回填区间应覆盖"中国"二字（下标 3..4）。
        assertEquals(listOf(3..4), res[0].ranges)
    }

    @Test
    fun initialsMatch() {
        // "微信输入法"首字母 wxshurufa（以 TinyPinyin 实际输出为准，至少命中 "wx" 前缀）。
        assertTrue(ClipboardSearchEngine.matches("微信输入法", "wx"))
        assertTrue(ClipboardSearchEngine.matches("微信输入法", "WX"))
        assertFalse(ClipboardSearchEngine.matches("微信输入法", "sg"))
        val res = ClipboardSearchEngine.search(listOf("微信输入法很好用"), "wx")
        assertEquals(1, res.size)
        assertTrue(res[0].ranges.isNotEmpty())
        assertEquals(listOf(0..1), res[0].ranges)
    }

    @Test
    fun limitTruncation() {
        val contents = List(10) { "item $it" }
        val res = ClipboardSearchEngine.search(contents, "item", limit = 3)
        assertEquals(3, res.size)
        assertEquals(listOf(0, 1, 2), res.map { it.index })
        assertTrue(res.all { it.ranges.isNotEmpty() })
        assertTrue(ClipboardSearchEngine.search(contents, "item", limit = 0).isEmpty())
    }

    @Test
    fun rangesNonEmptyOnMatch() {
        val contents = listOf("hello world", "微信输入法", "abcdef")
        val res = ClipboardSearchEngine.search(contents, "o")
        assertTrue(res.isNotEmpty())
        assertTrue(res.all { it.ranges.isNotEmpty() })
    }

    @Test
    fun bench100k() {
        val rnd = Random(42)
        val pool = listOf(
            "微信输入法", "剪贴板历史", "测试文本条目", "hello world", "clipboard item",
            "今天天气不错", "中国北京", "广州深圳", "abcdef", "123456"
        )
        val contents = List(100_000) { i ->
            "条目$i ${pool[i % pool.size]} ${pool[(i * 7 + rnd.nextInt(3)) % pool.size]} tail-$i"
        }
        // 预热：JIT 与 TinyPinyin 的静态初始化留在计时与计量之外。
        ClipboardSearchEngine.search(contents, "tiaomu")
        ClipboardSearchEngine.search(contents, "qqqqzzzz914")

        // 命中：tiaomu 为"条目"全拼，字面无此串，走拼音路径。
        var t0 = System.nanoTime()
        val hit = ClipboardSearchEngine.search(contents, "tiaomu")
        val hitMs = (System.nanoTime() - t0) / 1_000_000
        println("bench100k hit: size=${hit.size} time=${hitMs}ms")
        // 不命中：全量扫描最重路径。
        t0 = System.nanoTime()
        val miss = ClipboardSearchEngine.search(contents, "qqqqzzzz914")
        val missMs = (System.nanoTime() - t0) / 1_000_000
        println("bench100k miss: size=${miss.size} time=${missMs}ms")

        assertTrue("hit should find results", hit.isNotEmpty())
        assertTrue("miss should be empty", miss.isEmpty())

        // 未命中路径的开销按「每条内容分配多少字节」判，不按墙钟判：
        // 计时阈值取决于 runner 的 CPU，同一份代码在开发机 501ms、CI 501ms 就已经双双超过
        // 早先定的 300ms，只会随机变红。分配量对机器速度不敏感，能真正挡住「每条内容
        // 多分配一个结果对象」这类退化（未命中路径不该构造 hits / IntRange）。
        val missAlloc = allocatedBytes {
            ClipboardSearchEngine.search(contents, "qqqqzzzz914")
        }
        if (missAlloc != null) {
            val perItem = missAlloc / contents.size
            println("bench100k miss alloc: ${perItem}B/item (total ${missAlloc}B)")
            assertTrue(
                "miss path allocates ${perItem}B per item",
                perItem < MISS_ALLOC_BYTES_PER_ITEM
            )
        }
    }

    /**
     * 当前线程执行 [block] 期间分配的对象字节数；JVM 不支持时返回 null（调用方跳过断言）。
     *
     * 走反射是因为 Android 单元测试的编译类路径只有 android.jar，不含 `java.lang.management`。
     */
    private fun allocatedBytes(block: () -> Unit): Long? {
        return try {
            val bean = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean")
                .invoke(null)
            // 分配计量相关的四个方法都在 com.sun.management.ThreadMXBean 上，标准 ThreadMXBean 没有。
            val mbean = Class.forName("com.sun.management.ThreadMXBean")
            val supported =
                mbean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
            if (!supported) return null
            mbean.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                .invoke(bean, true)
            val current = mbean.getMethod("getCurrentThreadAllocatedBytes")
            val before = current.invoke(bean) as Long
            block()
            (current.invoke(bean) as Long) - before
        } catch (t: Throwable) {
            println("alloc probe unavailable: $t")
            null
        }
    }

    private companion object {
        /**
         * 未命中路径每条内容的分配上限。实测约 250B/条（JDK 17 与 21 同一量级），
         * 留 3 倍余量：只挡成倍退化（例如把结果对象或额外字符串塞进逐条路径），
         * 不追求抓单个小对象——那种精度换个 JDK 就会误报。
         */
        const val MISS_ALLOC_BYTES_PER_ITEM = 800L
    }
}
