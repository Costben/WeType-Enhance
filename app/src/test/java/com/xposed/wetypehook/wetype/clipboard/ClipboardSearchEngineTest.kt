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
        assertTrue("hit search ${hitMs}ms >= 300ms", hitMs < 300)
        assertTrue("miss search ${missMs}ms >= 300ms", missMs < 300)
    }
}
