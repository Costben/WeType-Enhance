package com.xposed.wetypehook.wetype.clipboard

import com.github.promeg.pinyinhelper.Pinyin
import java.util.Locale

/**
 * 剪贴板搜索内核（Slice 2）。
 *
 * 纯 Kotlin + TinyPinyin，不碰 Xposed/Adapter/View。
 * 上游语义见 S1 证据：文本条目即 `C.getType()==0`，[getContent()][String] 为搜索唯一范围；
 * 主过滤点 `ImeClipboardScrollView.setList`（S4/S5 消费本引擎结果）。
 */
data class MatchResult(val index: Int, val ranges: List<IntRange>)

object ClipboardSearchEngine {
    fun normalize(s: String): String = s.trim().lowercase(Locale.ROOT)

    fun matches(content: String, keyword: String): Boolean {
        val kw = normalize(keyword)
        if (kw.isEmpty()) return true
        return matchRanges(content, kw) != null
    }

    fun search(contents: List<String>, keyword: String, limit: Int = 50): List<MatchResult> {
        if (limit <= 0) return emptyList()
        val kw = normalize(keyword)
        if (kw.isEmpty()) {
            val n = minOf(limit, contents.size)
            val out = ArrayList<MatchResult>(n)
            for (i in 0 until n) out.add(MatchResult(i, emptyList()))
            return out
        }
        val out = ArrayList<MatchResult>(minOf(limit, contents.size))
        for (i in contents.indices) {
            val ranges = matchRanges(contents[i], kw) ?: continue
            out.add(MatchResult(i, ranges))
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * @param kw 已 normalize（trim + 小写）的关键词，非空。
     * @return 命中时返回 content 下标区间（供 S5 高亮），未命中返回 null。
     */
    private fun matchRanges(content: String, kw: String): List<IntRange>? {
        if (content.isEmpty()) return null
        // 1. 不分大小写包含命中。
        val lower = content.lowercase(Locale.ROOT)
        val idx = lower.indexOf(kw)
        if (idx >= 0) {
            val end = (idx + kw.length - 1).coerceAtMost(content.length - 1)
            if (idx < content.length && end >= idx) return listOf(idx..end)
            return null
        }
        // 2. 拼音命中：关键词须为 ASCII 词（拼音/首字母），含汉字则字面已判负，直接返回。
        if (!isAsciiWord(kw)) return null
        if (!hasChinese(content)) return null
        // 单遍构建全拼串与首字母串（未命中路径不做下标回填分配）。
        val full = StringBuilder(content.length * 4)
        val initials = StringBuilder(content.length)
        for (c in content) {
            if (Pinyin.isChinese(c)) {
                val p = Pinyin.toPinyin(c)
                var first = true
                for (pc in p) {
                    val lc = if (pc in 'A'..'Z') (pc + 32) else pc
                    full.append(lc)
                    if (first) {
                        initials.append(lc)
                        first = false
                    }
                }
            } else {
                val lc = if (c in 'A'..'Z') (c + 32) else c.lowercaseChar()
                full.append(lc)
                initials.append(lc)
            }
        }
        val pIdx = full.indexOf(kw)
        if (pIdx >= 0) {
            val range = mapFullPinyinToRange(content, pIdx, pIdx + kw.length)
            if (range != null) return listOf(range)
        }
        val iIdx = initials.indexOf(kw)
        if (iIdx >= 0 && iIdx + kw.length <= content.length) {
            return listOf(iIdx until iIdx + kw.length)
        }
        return null
    }

    private fun isAsciiWord(s: String): Boolean {
        for (c in s) {
            if ((c in 'a'..'z') || (c in '0'..'9')) continue
            return false
        }
        return true
    }

    private fun hasChinese(s: String): Boolean {
        for (c in s) {
            if (Pinyin.isChinese(c)) return true
        }
        return false
    }

    /** 把全拼串上的 [pStart, pEnd) 回填为 content 的汉字区间。 */
    private fun mapFullPinyinToRange(content: String, pStart: Int, pEnd: Int): IntRange? {
        var pos = 0
        var s = -1
        var e = -1
        for (i in content.indices) {
            val piece = if (Pinyin.isChinese(content[i])) Pinyin.toPinyin(content[i]).length else 1
            val start = pos
            pos += piece
            if (s < 0 && pos > pStart) s = i
            if (start < pEnd) e = i
        }
        if (s < 0 || e < 0 || e < s) return null
        return s..e
    }
}
