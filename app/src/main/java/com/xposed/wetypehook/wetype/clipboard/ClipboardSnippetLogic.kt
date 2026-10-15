package com.xposed.wetypehook.wetype.clipboard

/**
 * 剪贴板搜索「命中片段」的纯算术：卡片上看得见多少字、命中看不看得见、片段从哪里开始。
 *
 * 不碰 `android.text.Layout`，这样规则能在 JVM 单测里跑；调用方把排版量出来的数填进来。
 */
object ClipboardSnippetLogic {

    /**
     * 卡片上看得见的文字到哪个下标为止（不含）。
     *
     * 两种排版都要认：
     * - [lineCount] **多于** [maxLines]：这是 `maxLines` 封顶之前的旧排版（`maxLines` 改小后 `TextView`
     *   不一定重建排版，视图只是按前 `maxLines` 行的高度裁剪），看得见的就是前 [maxLines] 行，
     *   终点是第 `maxLines` 行的行尾 [capLineEnd]；
     * - [lineCount] 不多于 [maxLines]：整段都排下了，或者最后一行带省略号。**不能只看 [lastLineEnd]**：
     *   末行被省略号截断时它仍是整段文本的末尾，深层命中会被误判成「看得见」；有省略号时可见文字的终点是
     *   `[lastLineStart] + [ellipsisStart]`。
     */
    fun visibleEnd(
        lineCount: Int,
        maxLines: Int,
        capLineEnd: Int,
        lastLineStart: Int,
        lastLineEnd: Int,
        ellipsisStart: Int,
        ellipsisCount: Int
    ): Int {
        if (lineCount <= 0) return 0
        if (lineCount > maxLines) return capLineEnd
        return if (ellipsisCount > 0) lastLineStart + ellipsisStart else lastLineEnd
    }

    /** 命中的最后一个字符 [matchEnd] 落在可见文字之外。 */
    fun isMatchHidden(matchEnd: Int, visibleEnd: Int): Boolean = matchEnd >= visibleEnd

    /**
     * 片段起点：让命中落在窗口中间，前后都有上下文，而且窗口排满卡片。
     *
     * [capacity] 是原卡片能显示的字数（[visibleEnd]）。起点取「命中往前半个窗口」，但不越过
     * `[textLength] - [capacity]` —— 命中靠近原文末尾时，窗口贴着原文末尾往前取，而不是只剩命中后面
     * 的两三个字（实机：命中在 58 字原文的第 55 位，从命中前 3 个字切出来只有一行 `…t首T这么高`，
     * 卡片上下全是空的）。
     */
    fun snippetWindowStart(matchStart: Int, textLength: Int, capacity: Int): Int {
        val maxStart = (textLength - capacity).coerceAtLeast(0)
        return (matchStart - capacity / 2).coerceIn(0, maxStart)
    }

    /** 兜底的紧凑片段：从命中往前 [lead] 个字符开始（不越过开头），命中必然在第一行。 */
    fun snippetStart(matchStart: Int, lead: Int): Int = (matchStart - lead).coerceAtLeast(0)
}
